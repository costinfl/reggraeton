package io.reggraeton.generate;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.body.EnumDeclaration;
import io.reggraeton.model.Hook;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.scan.java.Expressions;
import io.reggraeton.scan.java.GlueAnnotations;
import io.reggraeton.translate.TranslatedSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * native mode: removes Cucumber from the translated glue code. Step and hook annotations are
 * dropped (methods stay and are called directly by the generated tests), cucumber-java8 lambda
 * registrations become ordinary methods, and Cucumber's {@code Scenario}/{@code DataTable} are
 * replaced by the runtime support equivalents.
 */
final class NativeGlueRewriter {

    /** A glue method the generated tests call. */
    record Target(String className, String methodName, List<String> parameterTypes) {
    }

    record Result(Map<String, String> sources, Map<String, Target> steps, Map<String, Target> hooks,
                  Set<String> enums, List<String> problems) {
    }

    private static final Pattern EXPRESSION_PARAMETER = Pattern.compile("\\{([^}]*)}");

    private final Inventory inventory;
    private final String supportPackage;
    private final Map<String, ParameterTypeDefinition> parameterTypes = new HashMap<>();
    private final Map<String, Target> stepTargets = new LinkedHashMap<>();
    private final Map<String, Target> hookTargets = new LinkedHashMap<>();
    private final Set<String> enums = new HashSet<>();
    private final List<String> problems = new ArrayList<>();
    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    NativeGlueRewriter(Inventory inventory, String supportPackage) {
        this.inventory = inventory;
        this.supportPackage = supportPackage;
        inventory.parameterTypes().forEach(p -> parameterTypes.put(p.name(), p));
    }

    Result rewrite(List<TranslatedSource> sources) {
        Map<String, String> rewritten = new LinkedHashMap<>();
        for (TranslatedSource source : sources) {
            ParseResult<CompilationUnit> parsed = parser.parse(source.source());
            if (parsed.getResult().isEmpty()) {
                problems.add(source.relativePath() + ": could not re-parse translated source");
                rewritten.put(source.relativePath(), source.source());
                continue;
            }
            CompilationUnit unit = parsed.getResult().get();
            unit.findAll(EnumDeclaration.class).forEach(e -> enums.add(e.getNameAsString()));
            rewrite(unit);
            rewritten.put(source.relativePath(), unit.toString());
        }
        for (StepDefinition definition : inventory.stepDefinitions()) {
            if (StepDefinition.ANNOTATION.equals(definition.style())) {
                stepTargets.putIfAbsent(definition.id(), new Target(definition.className(), definition.methodName(),
                        definition.parameters().stream().map(p -> p.type()).toList()));
            }
        }
        for (Hook hook : inventory.hooks()) {
            if (hook.methodName() != null) {
                hookTargets.putIfAbsent(hook.id(), new Target(hook.className(), hook.methodName(),
                        hook.parameters().stream().map(p -> p.type()).toList()));
            }
        }
        return new Result(rewritten, stepTargets, hookTargets, enums, problems);
    }

    private void rewrite(CompilationUnit unit) {
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            for (MethodDeclaration method : type.getMethods()) {
                stripGlueAnnotations(method);
            }
            if (type instanceof ClassOrInterfaceDeclaration cls && usesLambdaGlue(unit, cls)) {
                convertLambdaGlue(unit, cls);
            }
        }
        NodeList<ImportDeclaration> imports = unit.getImports();
        boolean scenario = imports.stream().anyMatch(i -> i.getNameAsString().equals("io.cucumber.java.Scenario"));
        boolean dataTable = imports.stream().anyMatch(i -> i.getNameAsString().equals("io.cucumber.datatable.DataTable"));
        boolean pending = imports.stream().anyMatch(i -> i.getNameAsString().equals("io.cucumber.java.PendingException"));
        imports.removeIf(i -> i.getNameAsString().startsWith("io.cucumber.") || i.getNameAsString().startsWith("cucumber.api."));
        if (scenario) {
            unit.addImport(supportPackage + ".Scenario");
        }
        if (dataTable || mentions(unit, "DataTable")) {
            unit.addImport(supportPackage + ".DataTable");
        }
        if (pending) {
            unit.addImport(supportPackage + ".PendingConversionException");
            unit.findAll(ClassOrInterfaceType.class).stream()
                    .filter(t -> t.getNameAsString().equals("PendingException"))
                    .forEach(t -> t.setName("PendingConversionException"));
        }
    }

    private static boolean mentions(CompilationUnit unit, String simpleName) {
        return unit.findAll(ClassOrInterfaceType.class).stream().anyMatch(t -> t.getNameAsString().equals(simpleName));
    }

    private static void stripGlueAnnotations(MethodDeclaration method) {
        List<String> described = new ArrayList<>();
        for (AnnotationExpr annotation : new ArrayList<>(method.getAnnotations())) {
            String qualified = Expressions.qualifiedAnnotationName(annotation);
            boolean step = GlueAnnotations.isStepAnnotation(qualified)
                    || (!qualified.contains(".") && GlueAnnotations.isUnqualifiedStepKeyword(qualified));
            boolean glue = step || GlueAnnotations.hookPhase(qualified) != null
                    || GlueAnnotations.isParameterType(qualified) || GlueAnnotations.transformerKind(qualified) != null;
            if (glue) {
                described.add(annotation.toString());
                annotation.remove();
            }
        }
        if (!described.isEmpty() && method.getComment().isEmpty()) {
            method.setComment(new com.github.javaparser.ast.comments.TraditionalJavadocComment(" Was: " + JavaLiterals.comment(String.join(" ", described)) + " "));
        }
    }

    private static boolean usesLambdaGlue(CompilationUnit unit, ClassOrInterfaceDeclaration cls) {
        boolean imported = unit.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("io.cucumber.java8")
                || i.getNameAsString().startsWith("cucumber.api.java8"));
        return imported || cls.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("En"));
    }

    // ----------------------------------------------------------------------------------------
    // cucumber-java8 lambdas -> methods
    // ----------------------------------------------------------------------------------------

    private void convertLambdaGlue(CompilationUnit unit, ClassOrInterfaceDeclaration cls) {
        String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
        cls.getImplementedTypes().removeIf(t -> t.getNameAsString().equals("En")
                || t.getNameWithScope().startsWith("io.cucumber.java8") || t.getNameWithScope().startsWith("cucumber.api.java8"));
        List<StepDefinition> lambdaSteps = inventory.stepDefinitions().stream()
                .filter(s -> s.className().equals(className) && StepDefinition.LAMBDA.equals(s.style()))
                .toList();
        List<Hook> lambdaHooks = inventory.hooks().stream()
                .filter(h -> h.className().equals(className) && h.methodName() == null)
                .sorted((a, b) -> Integer.compare(a.line(), b.line()))
                .toList();
        Set<String> usedNames = new HashSet<>();
        cls.getMethods().forEach(m -> usedNames.add(m.getNameAsString()));
        int hookIndex = 0;

        for (ConstructorDeclaration ctor : cls.getConstructors()) {
            BlockStmt body = ctor.getBody();
            List<MethodDeclaration> created = new ArrayList<>();
            Set<String> captured = new HashSet<>();
            for (Statement stmt : new ArrayList<>(body.getStatements())) {
                Optional<MethodCallExpr> registration = registration(stmt);
                if (registration.isEmpty()) {
                    continue;
                }
                MethodCallExpr call = registration.get();
                String name = call.getNameAsString();
                LambdaExpr lambda = (LambdaExpr) call.getArgument(call.getArguments().size() - 1);
                lambda.findAll(NameExpr.class).forEach(n -> captured.add(n.getNameAsString()));
                if (GlueAnnotations.LAMBDA_STEP_METHODS.contains(name)) {
                    String expression = Expressions.constantString(call.getArgument(0));
                    Optional<StepDefinition> definition = lambdaSteps.stream()
                            .filter(s -> s.keyword().equals(name) && s.expression().equals(expression)).findFirst();
                    if (definition.isEmpty()) {
                        problems.add(className + ": no step definition found for lambda " + name + "(\"" + expression + "\")");
                        continue;
                    }
                    String methodName = unique(stepMethodName(expression), usedNames);
                    List<String> types = parameterTypes(definition.get(), lambda);
                    MethodDeclaration method = method(methodName, lambda, types);
                    method.setComment(new com.github.javaparser.ast.comments.TraditionalJavadocComment(" Was: " + JavaLiterals.comment(name + "(\"" + expression + "\", ...)") + " "));
                    created.add(method);
                    stepTargets.put(definition.get().id(), new Target(className, methodName, types));
                    stmt.remove();
                } else if (GlueAnnotations.HOOK_PHASES.contains(name)) {
                    Hook hook = hookIndex < lambdaHooks.size() ? lambdaHooks.get(hookIndex) : null;
                    hookIndex++;
                    String methodName = unique(Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Hook", usedNames);
                    List<String> types = lambda.getParameters().stream()
                            .map(p -> p.getType() instanceof UnknownType ? "Scenario" : p.getType().asString()).toList();
                    created.add(method(methodName, lambda, types));
                    if (hook != null) {
                        hookTargets.put(hook.id(), new Target(className, methodName, types));
                    }
                    stmt.remove();
                } else if (name.equals("ParameterType") && call.getArguments().size() >= 3) {
                    String typeName = Expressions.constantString(call.getArgument(0));
                    String methodName = unique(typeName == null ? "parameterType" : TestNaming.memberName(typeName), usedNames);
                    MethodDeclaration method = method(methodName, lambda,
                            lambda.getParameters().stream().map(p -> p.getType() instanceof UnknownType ? "String" : p.getType().asString()).toList());
                    method.setType("Object");
                    if (method.getBody().isPresent() && lambda.getExpressionBody().isPresent()) {
                        method.setBody(new BlockStmt(new NodeList<>(new com.github.javaparser.ast.stmt.ReturnStmt(lambda.getExpressionBody().get().clone()))));
                    }
                    created.add(method);
                    if (typeName != null && parameterTypes.containsKey(typeName)) {
                        ParameterTypeDefinition old = parameterTypes.get(typeName);
                        parameterTypes.put(typeName, new ParameterTypeDefinition(old.name(), old.regexps(), className, methodName,
                                "Object", old.file(), old.line()));
                    }
                    stmt.remove();
                } else {
                    problems.add(className + ": " + name + "(...) lambda registration is not converted in native mode");
                    stmt.remove();
                }
            }
            promoteToFields(cls, ctor, captured);
            created.forEach(cls::addMember);
        }
    }

    /** Converted parameter types (custom parameter types replaced for the native lambdas). */
    Map<String, ParameterTypeDefinition> parameterTypes() {
        return parameterTypes;
    }

    private static Optional<MethodCallExpr> registration(Statement stmt) {
        if (stmt instanceof ExpressionStmt e && e.getExpression() instanceof MethodCallExpr call
                && (call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr)
                && !call.getArguments().isEmpty()
                && call.getArgument(call.getArguments().size() - 1) instanceof LambdaExpr) {
            return Optional.of(call);
        }
        return Optional.empty();
    }

    private static MethodDeclaration method(String name, LambdaExpr lambda, List<String> types) {
        MethodDeclaration method = new MethodDeclaration();
        method.setName(name);
        method.setModifiers(Modifier.Keyword.PUBLIC);
        method.setType("void");
        for (int i = 0; i < lambda.getParameters().size(); i++) {
            Parameter p = lambda.getParameter(i);
            method.addParameter(new Parameter(parseType(types.get(i)), p.getNameAsString()));
        }
        if (lambda.getBody() instanceof BlockStmt block) {
            method.setBody(block.clone());
        } else if (lambda.getExpressionBody().isPresent()) {
            method.setBody(new BlockStmt(new NodeList<>(new ExpressionStmt(lambda.getExpressionBody().get().clone()))));
        }
        method.addThrownException(new ClassOrInterfaceType(null, "Throwable"));
        return method;
    }

    private static Type parseType(String type) {
        return com.github.javaparser.StaticJavaParser.parseType(type);
    }

    /** Explicit lambda parameter types, or types inferred from the step pattern. */
    private List<String> parameterTypes(StepDefinition definition, LambdaExpr lambda) {
        List<String> inferred = inferTypes(definition);
        List<String> types = new ArrayList<>();
        for (int i = 0; i < lambda.getParameters().size(); i++) {
            Parameter p = lambda.getParameter(i);
            if (!(p.getType() instanceof UnknownType)) {
                types.add(p.getType().asString());
            } else {
                types.add(i < inferred.size() ? inferred.get(i) : "DataTable");
            }
        }
        return types;
    }

    private List<String> inferTypes(StepDefinition definition) {
        List<String> types = new ArrayList<>();
        if (StepDefinition.REGULAR_EXPRESSION.equals(definition.expressionType())) {
            Matcher groups = Pattern.compile("\\((?!\\?)").matcher(definition.expression());
            while (groups.find()) {
                types.add("String");
            }
            return types;
        }
        Matcher m = EXPRESSION_PARAMETER.matcher(definition.expression());
        while (m.find()) {
            String name = m.group(1);
            types.add(switch (name) {
                case "int" -> "Integer";
                case "long" -> "Long";
                case "float" -> "Float";
                case "double" -> "Double";
                case "short" -> "Short";
                case "byte" -> "Byte";
                case "bigdecimal" -> "java.math.BigDecimal";
                case "biginteger" -> "java.math.BigInteger";
                case "string", "word", "" -> "String";
                default -> {
                    ParameterTypeDefinition custom = parameterTypes.get(name);
                    yield custom != null && custom.returnType() != null && !custom.returnType().equals("?") ? custom.returnType() : "Object";
                }
            });
        }
        return types;
    }

    /** Constructor parameters and locals used by the former lambdas become fields. */
    private static void promoteToFields(ClassOrInterfaceDeclaration cls, ConstructorDeclaration ctor, Set<String> captured) {
        Set<String> fields = new HashSet<>();
        cls.getFields().forEach(f -> f.getVariables().forEach(v -> fields.add(v.getNameAsString())));
        BlockStmt body = ctor.getBody();
        int insertAt = body.getStatements().isNonEmpty() && body.getStatement(0) instanceof ExplicitConstructorInvocationStmt ? 1 : 0;
        for (Parameter parameter : ctor.getParameters()) {
            String name = parameter.getNameAsString();
            if (captured.contains(name) && !fields.contains(name)) {
                cls.getMembers().addFirst(new com.github.javaparser.ast.body.FieldDeclaration(
                        new NodeList<>(Modifier.privateModifier(), Modifier.finalModifier()),
                        new VariableDeclarator(parameter.getType().clone(), name)));
                body.addStatement(insertAt++, new ExpressionStmt(new AssignExpr(
                        new FieldAccessExpr(new ThisExpr(), name), new NameExpr(name), AssignExpr.Operator.ASSIGN)));
                fields.add(name);
            }
        }
        for (Statement stmt : new ArrayList<>(body.getStatements())) {
            if (stmt instanceof ExpressionStmt e && e.getExpression() instanceof VariableDeclarationExpr decl) {
                List<Statement> assignments = new ArrayList<>();
                for (VariableDeclarator v : decl.getVariables()) {
                    String name = v.getNameAsString();
                    if (!captured.contains(name) || fields.contains(name) || v.getType().isVarType()) {
                        continue;
                    }
                    cls.getMembers().addFirst(new com.github.javaparser.ast.body.FieldDeclaration(
                            new NodeList<>(Modifier.privateModifier()), new VariableDeclarator(v.getType().clone(), name)));
                    fields.add(name);
                    v.getInitializer().ifPresent(init -> assignments.add(new ExpressionStmt(new AssignExpr(
                            new FieldAccessExpr(new ThisExpr(), name), init.clone(), AssignExpr.Operator.ASSIGN))));
                }
                if (!assignments.isEmpty() && decl.getVariables().size() == 1) {
                    int i = body.getStatements().indexOf(stmt);
                    body.getStatements().remove(i);
                    body.getStatements().addAll(i, assignments);
                }
            }
        }
        if (body.getStatements().isEmpty()) {
            body.addOrphanComment(new LineComment(" step definitions are methods of this class"));
        }
    }

    /** {@code "I add {string} to the cart"} gives {@code iAddToTheCart}. */
    static String stepMethodName(String expression) {
        String words = expression
                .replaceAll("\\{[^}]*}", " ")      // cucumber expression parameters
                .replaceAll("\\([^)]*\\)", " ")  // optional text and regex groups
                .replaceAll("\\[[^]]*]", " ")      // regex character classes
                .replaceAll("\\\\.", " ");          // regex escapes
        String name = TestNaming.memberName(words);
        return name.equals("scenario") ? "step" : name;
    }

    private static String unique(String base, Set<String> used) {
        String name = base;
        for (int i = 2; !used.add(name); i++) {
            name = base + i;
        }
        return name;
    }
}

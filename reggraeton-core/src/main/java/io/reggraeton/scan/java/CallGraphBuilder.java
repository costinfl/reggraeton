package io.reggraeton.scan.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.CallGraph.Edge;
import io.reggraeton.model.Hook;
import io.reggraeton.model.StepDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the call graph: one node per project method / constructor / glue lambda, a class node for
 * field-level locators ({@code @FindBy}, {@code By} constants), edges for calls into project code,
 * and the external API usages of every node.
 */
public final class CallGraphBuilder {

    public static final String KIND_STEP = "step";
    public static final String KIND_HOOK = "hook";
    public static final String KIND_METHOD = "method";
    public static final String KIND_CONSTRUCTOR = "constructor";
    public static final String KIND_CLASS = "class";

    /** Method names too generic to report when the receiver type is unknown. */
    private static final Set<String> TOO_GENERIC = Set.of("get", "to", "build", "perform", "until", "close");

    private final JavaSourceIndex index;
    private final GlueScanner.Glue glue;

    private final Map<String, CallGraph.Node> nodes = new LinkedHashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();
    private final Set<Node> excludedBodies = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, List<MethodDeclaration>> methodsByName = new HashMap<>();
    private final Map<String, String> classNodeIds = new HashMap<>();
    private final Map<String, List<String>> typesBySimpleName = new HashMap<>();

    public CallGraphBuilder(JavaSourceIndex index, GlueScanner.Glue glue) {
        this.index = index;
        this.glue = glue;
    }

    /** A final field initialised with SQL; attributed to the methods that read it. */
    private record SqlConstant(String className, com.github.javaparser.ast.body.VariableDeclarator declarator, String text) {
    }

    private final Map<String, List<SqlConstant>> sqlConstants = new HashMap<>();

    private void findSqlConstants() {
        for (Map.Entry<String, TypeDeclaration<?>> type : index.types().entrySet()) {
            for (FieldDeclaration field : type.getValue().getFields()) {
                if (!field.isFinal()) {
                    continue;
                }
                for (var v : field.getVariables()) {
                    v.getInitializer().filter(SqlLiterals::isTop).map(SqlLiterals::text)
                            .filter(text -> !io.reggraeton.scan.db.Sql.operations(text, true).isEmpty())
                            .ifPresent(text -> sqlConstants.computeIfAbsent(v.getNameAsString(), k -> new ArrayList<>())
                                    .add(new SqlConstant(type.getKey(), v, text)));
                }
            }
        }
    }

    public CallGraph build() {
        findSqlConstants();
        for (Map.Entry<String, TypeDeclaration<?>> type : index.types().entrySet()) {
            typesBySimpleName.computeIfAbsent(type.getValue().getNameAsString(), k -> new ArrayList<>()).add(type.getKey());
            for (MethodDeclaration m : type.getValue().getMethods()) {
                methodsByName.computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>()).add(m);
            }
        }
        glue.bodies().values().stream().filter(LambdaExpr.class::isInstance).forEach(excludedBodies::add);
        glue.bodies().values().stream().filter(MethodReferenceExpr.class::isInstance).forEach(excludedBodies::add);

        Map<Node, String> glueKind = new IdentityHashMap<>();
        for (StepDefinition s : glue.stepDefinitions()) {
            glueKind.put(glue.bodies().get(s.id()), KIND_STEP);
        }
        for (Hook h : glue.hooks()) {
            glueKind.put(glue.bodies().get(h.id()), KIND_HOOK);
        }

        // class nodes first, so constructors can link to them
        for (Map.Entry<String, TypeDeclaration<?>> entry : index.types().entrySet()) {
            classNode(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, TypeDeclaration<?>> entry : index.types().entrySet()) {
            String className = entry.getKey();
            TypeDeclaration<?> type = entry.getValue();
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof ConstructorDeclaration ctor) {
                    String id = JavaSourceIndex.callableId(ctor);
                    Collector c = collect(ctor, id);
                    addNode(id, KIND_CONSTRUCTOR, className, "<init>", ctor, c.usages);
                    String classNode = classNodeIds.get(className);
                    if (classNode != null) {
                        edges.add(new Edge(id, classNode, JavaSourceIndex.lineOf(ctor), true));
                    }
                } else if (member instanceof MethodDeclaration method) {
                    String id = JavaSourceIndex.callableId(method);
                    Collector c = collect(method, id);
                    addNode(id, glueKind.getOrDefault(method, KIND_METHOD), className, method.getNameAsString(), method, c.usages);
                }
            }
        }
        // glue registered as lambdas / method references, and step ids that differ from the method id
        for (StepDefinition s : glue.stepDefinitions()) {
            addGlueNode(s.id(), KIND_STEP, s.className(), glue.bodies().get(s.id()));
        }
        for (Hook h : glue.hooks()) {
            addGlueNode(h.id(), KIND_HOOK, h.className(), glue.bodies().get(h.id()));
        }

        // one edge per (caller, callee); the first call site's line is kept
        Map<String, Edge> unique = new LinkedHashMap<>();
        for (Edge e : edges) {
            if (nodes.containsKey(e.to())) {
                unique.merge(e.from() + "->" + e.to(), e,
                        (a, b) -> a.resolved() || !b.resolved() ? a : b);
            }
        }
        List<Edge> edgeList = new ArrayList<>(unique.values());
        return new CallGraph(new ArrayList<>(nodes.values()), edgeList);
    }

    private void addGlueNode(String id, String kind, String className, Node body) {
        if (nodes.containsKey(id) || body == null) {
            return;
        }
        if (body instanceof MethodDeclaration method) {
            // one method carrying several step annotations: link the extra ids to the method node
            addNode(id, kind, className, method.getNameAsString(), method, List.of());
            edges.add(new Edge(id, JavaSourceIndex.callableId(method), JavaSourceIndex.lineOf(method), true));
            return;
        }
        Collector c = new Collector(id);
        c.walk(body, true);
        if (body instanceof MethodReferenceExpr ref) {
            c.methodReference(ref);
        }
        addNode(id, kind, className, id.substring(id.indexOf('#') + 1), body, c.usages);
    }

    private void addNode(String id, String kind, String className, String name, Node node, List<ApiUsage> usages) {
        nodes.put(id, new CallGraph.Node(id, kind, className, name, index.pathOf(node), JavaSourceIndex.lineOf(node), usages));
    }

    /** Class-level node holding field locators and field initialisers; created only when non-empty. */
    private void classNode(String className, TypeDeclaration<?> type) {
        Collector c = new Collector(className);
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                for (AnnotationExpr annotation : field.getAnnotations()) {
                    String qualified = Expressions.qualifiedAnnotationName(annotation);
                    if (qualified.startsWith("org.openqa.selenium.support.")) {
                        String api = "@" + annotation.getNameAsString();
                        SeleniumApiCatalog.Support support = SeleniumApiCatalog.classify(api, null);
                        c.usages.add(new ApiUsage(ApiUsage.SELENIUM, api, code(field), JavaSourceIndex.lineOf(field),
                                support.auto() ? ApiUsage.AUTO : ApiUsage.MANUAL, support.note()));
                    }
                }
                field.getVariables().forEach(v -> v.getInitializer().ifPresent(init -> {
                    if (!(field.isFinal() && sqlConstants.containsKey(v.getNameAsString())
                            && sqlConstants.get(v.getNameAsString()).stream().anyMatch(k -> k.declarator() == v))) {
                        c.walk(init, false); // SQL constants count where they are used
                    }
                }));
            } else if (member instanceof InitializerDeclaration init) {
                c.walk(init.getBody(), false);
            }
        }
        boolean hasProjectSuper = superClassNode(type).isPresent();
        if (!c.usages.isEmpty() || hasProjectSuper) {
            classNodeIds.put(className, className);
            addNode(className, KIND_CLASS, className, JavaSourceIndex.withoutPackage(className), type, c.usages);
        }
        superClassNode(type).ifPresent(superName ->
                edges.add(new Edge(className, superName, JavaSourceIndex.lineOf(type), true)));
    }

    private Optional<String> superClassNode(TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration cls) || cls.getExtendedTypes().isEmpty()) {
            return Optional.empty();
        }
        try {
            String qualified = cls.getExtendedTypes(0).resolve().asReferenceType().getQualifiedName();
            return index.isProjectType(qualified) ? Optional.of(qualified) : Optional.empty();
        } catch (RuntimeException | StackOverflowError e) {
            List<String> candidates = typesBySimpleName.getOrDefault(cls.getExtendedTypes(0).getNameAsString(), List.of());
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }
    }

    private Collector collect(CallableDeclaration<?> callable, String id) {
        Collector c = new Collector(id);
        c.walk(callable, true);
        return c;
    }

    // ----------------------------------------------------------------------------------------

    /** Walks one body and records edges and API usages for node {@code from}. */
    private final class Collector {
        final String from;
        final List<ApiUsage> usages = new ArrayList<>();

        Collector(String from) {
            this.from = from;
        }

        void walk(Node node, boolean root) {
            if (!root && excludedBodies.contains(node)) {
                return;
            }
            if (!root && node instanceof TypeDeclaration<?>) {
                return; // nested / local types have their own nodes
            }
            if (node instanceof MethodCallExpr call) {
                methodCall(call);
            } else if (node instanceof ObjectCreationExpr creation) {
                objectCreation(creation);
            } else if (node instanceof ExplicitConstructorInvocationStmt invocation) {
                explicitConstructor(invocation);
            } else if (node instanceof MethodReferenceExpr ref && !root) {
                methodReference(ref);
            } else if (node instanceof Expression e && SqlLiterals.isTop(e)) {
                sql(e);
            } else if (node instanceof NameExpr name && sqlConstants.containsKey(name.getNameAsString())) {
                sqlConstant(name, name.getNameAsString());
            } else if (node instanceof com.github.javaparser.ast.expr.FieldAccessExpr access
                    && sqlConstants.containsKey(access.getNameAsString())) {
                sqlConstant(access, access.getNameAsString());
            }
            for (Node child : node.getChildNodes()) {
                walk(child, false);
            }
        }

        /** A read of an SQL constant: resolved when possible, else accepted when the name is unique. */
        void sqlConstant(Expression read, String name) {
            List<SqlConstant> candidates = sqlConstants.get(name);
            SqlConstant constant = null;
            try {
                var resolved = read instanceof NameExpr n ? n.resolve() : read.asFieldAccessExpr().resolve();
                if (resolved.isField()) {
                    String owner = resolved.asField().declaringType().getQualifiedName();
                    constant = candidates.stream().filter(k -> k.className().equals(owner)).findFirst().orElse(null);
                }
            } catch (RuntimeException | StackOverflowError e) {
                constant = candidates.size() == 1 ? candidates.get(0) : null;
            }
            if (constant != null) {
                sql(constant.text(), JavaSourceIndex.lineOf(read),
                        " (constant " + JavaSourceIndex.withoutPackage(constant.className()) + "." + name + ")");
            }
        }

        /** SQL in a string literal (or a concatenation of literals and variables). */
        void sql(Expression e) {
            sql(SqlLiterals.text(e), JavaSourceIndex.lineOf(e), "");
        }

        void sql(String text, int line, String origin) {
            java.util.List<String> ops = io.reggraeton.scan.db.Sql.operations(text, true);
            if (!ops.isEmpty()) {
                usages.add(new ApiUsage(ApiUsage.DATABASE, ApiUsage.SQL_PREFIX + ops.get(0),
                        io.reggraeton.scan.db.Sql.display(text, 300), line, ApiUsage.AUTO,
                        "SQL" + origin + ": " + String.join(", ", ops)
                                + (io.reggraeton.scan.db.Sql.writes(ops) ? " (changes data)" : "") + "; kept as is"));
            }
        }

        void methodCall(MethodCallExpr call) {
            int line = JavaSourceIndex.lineOf(call);
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                target(resolved, resolved.declaringType().getQualifiedName(), resolved.getName(), call, line);
                return;
            } catch (RuntimeException | StackOverflowError e) {
                // fall through to heuristics
            }
            String name = call.getNameAsString();
            int arity = call.getArguments().size();
            if (call.getScope().isEmpty()) {
                Optional<MethodDeclaration> local = findInHierarchy(call, name, arity);
                if (local.isPresent()) {
                    edges.add(new Edge(from, JavaSourceIndex.callableId(local.get()), line, false));
                    return;
                }
                staticImportOwner(call, name).ifPresent(owner -> usage(owner, name, call, line));
                return;
            }
            Expression scope = call.getScope().get();
            String scopeType = scopeType(scope);
            if (scopeType != null) {
                if (index.isProjectType(scopeType)) {
                    uniqueByName(name, arity, scopeType).ifPresent(m ->
                            edges.add(new Edge(from, JavaSourceIndex.callableId(m), line, false)));
                } else {
                    usage(scopeType, name, call, line);
                }
                return;
            }
            Optional<MethodDeclaration> byName = uniqueByName(name, arity, null);
            if (byName.isPresent()) {
                edges.add(new Edge(from, JavaSourceIndex.callableId(byName.get()), line, false));
            } else if (SeleniumApiCatalog.isKnownMethodName(name) && !TOO_GENERIC.contains(name)) {
                usages.add(new ApiUsage(ApiUsage.SELENIUM, "?." + name, code(call), line, ApiUsage.MANUAL,
                        "receiver type could not be resolved; looks like Selenium, check manually"));
            }
        }

        void objectCreation(ObjectCreationExpr creation) {
            int line = JavaSourceIndex.lineOf(creation);
            try {
                ResolvedConstructorDeclaration resolved = creation.resolve();
                target(resolved, resolved.declaringType().getQualifiedName(), "<init>", creation, line);
                return;
            } catch (RuntimeException | StackOverflowError e) {
                // heuristics below
            }
            String simple = creation.getType().getNameAsString();
            List<String> candidates = typesBySimpleName.getOrDefault(simple, List.of());
            if (candidates.size() == 1) {
                edges.add(new Edge(from, constructorTarget(candidates.get(0), creation.getArguments().size()), line, false));
                return;
            }
            importedType(creation, simple).ifPresent(owner -> usage(owner, "<init>", creation, line, code(creation)));
        }

        void explicitConstructor(ExplicitConstructorInvocationStmt invocation) {
            int line = JavaSourceIndex.lineOf(invocation);
            try {
                ResolvedConstructorDeclaration resolved = invocation.resolve();
                target(resolved, resolved.declaringType().getQualifiedName(), "<init>", null, line);
            } catch (RuntimeException | StackOverflowError e) {
                // ignore: super(...) into an unresolvable type
            }
        }

        void methodReference(MethodReferenceExpr ref) {
            int line = JavaSourceIndex.lineOf(ref);
            try {
                ResolvedMethodDeclaration resolved = ref.resolve();
                target(resolved, resolved.declaringType().getQualifiedName(), resolved.getName(), null, line);
            } catch (RuntimeException | StackOverflowError e) {
                uniqueByName(ref.getIdentifier(), -1, null).ifPresent(m ->
                        edges.add(new Edge(from, JavaSourceIndex.callableId(m), line, false)));
            }
        }

        private void target(ResolvedMethodLikeDeclaration resolved, String declaringType, String member,
                            Expression call, int line) {
            if (index.isProjectType(declaringType)) {
                Optional<Node> ast = resolved.toAst();
                String id = ast.filter(CallableDeclaration.class::isInstance)
                        .map(n -> JavaSourceIndex.callableId((CallableDeclaration<?>) n))
                        .orElseGet(() -> member.equals("<init>")
                                ? constructorTarget(declaringType, resolved.getNumberOfParams())
                                : declaringType + "#" + member + "(" + resolvedParams(resolved) + ")");
                edges.add(new Edge(from, id, line, true));
            } else {
                usage(declaringType, member, call, line);
            }
        }

        private void usage(String declaringType, String member, Expression call, int line) {
            usage(declaringType, member, call, line, call == null ? "" : code(call));
        }

        private void usage(String declaringType, String member, Expression call, int line, String code) {
            ApiUsage usage = ApiClassifier.classify(declaringType, member, code, line, call, index.policy());
            if (usage != null) {
                usages.add(usage);
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Heuristics used when the symbol solver cannot resolve a call
    // ----------------------------------------------------------------------------------------

    private String constructorTarget(String className, int arity) {
        TypeDeclaration<?> type = index.types().get(className);
        if (type != null) {
            List<ConstructorDeclaration> ctors = type.getConstructors();
            List<ConstructorDeclaration> sameArity = ctors.stream()
                    .filter(c -> c.getParameters().size() == arity).collect(Collectors.toList());
            if (sameArity.size() == 1) {
                return JavaSourceIndex.callableId(sameArity.get(0));
            }
        }
        return classNodeIds.getOrDefault(className, className);
    }

    private static String resolvedParams(ResolvedMethodLikeDeclaration resolved) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < resolved.getNumberOfParams(); i++) {
            try {
                params.add(JavaSourceIndex.simpleTypeName(resolved.getParam(i).describeType()));
            } catch (RuntimeException e) {
                params.add("?");
            }
        }
        return String.join(",", params);
    }

    private Optional<MethodDeclaration> findInHierarchy(Node from, String name, int arity) {
        Optional<TypeDeclaration> type = from.findAncestor(TypeDeclaration.class);
        Set<String> visited = new LinkedHashSet<>();
        while (type.isPresent()) {
            TypeDeclaration<?> t = type.get();
            String qualified = JavaSourceIndex.qualifiedName(t);
            if (!visited.add(qualified)) {
                break;
            }
            List<MethodDeclaration> matches = t.getMethodsByName(name).stream()
                    .filter(m -> arityMatches(m, arity)).collect(Collectors.toList());
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
            if (matches.size() > 1) {
                return Optional.empty();
            }
            type = superClassNode(t).map(s -> index.types().get(s));
        }
        return Optional.empty();
    }

    private Optional<MethodDeclaration> uniqueByName(String name, int arity, String inType) {
        List<MethodDeclaration> matches = methodsByName.getOrDefault(name, List.of()).stream()
                .filter(m -> arity < 0 || arityMatches(m, arity))
                .filter(m -> inType == null || m.findAncestor(TypeDeclaration.class)
                        .map(t -> JavaSourceIndex.qualifiedName((TypeDeclaration<?>) t).equals(inType)).orElse(false))
                .collect(Collectors.toList());
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static boolean arityMatches(MethodDeclaration m, int arity) {
        int n = m.getParameters().size();
        boolean varargs = n > 0 && m.getParameter(n - 1).isVarArgs();
        return varargs ? arity >= n - 1 : arity == n;
    }

    /** Qualified type of a call's scope expression, or null. */
    private String scopeType(Expression scope) {
        try {
            ResolvedType type = scope.calculateResolvedType();
            if (type.isReferenceType()) {
                return type.asReferenceType().getQualifiedName();
            }
            return null;
        } catch (RuntimeException | StackOverflowError e) {
            // static call on a type name, e.g. By.id(...) with an unresolvable import
            if (scope instanceof NameExpr name && Character.isUpperCase(name.getNameAsString().charAt(0))) {
                return importedType(scope, name.getNameAsString()).orElse(null);
            }
            return null;
        }
    }

    private Optional<String> importedType(Node node, String simpleName) {
        List<String> local = typesBySimpleName.getOrDefault(simpleName, List.of());
        if (local.size() == 1) {
            return Optional.of(local.get(0));
        }
        return node.findCompilationUnit().flatMap(cu -> cu.getImports().stream()
                .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(simpleName))
                .map(ImportDeclaration::getNameAsString)
                .findFirst());
    }

    private static Optional<String> staticImportOwner(Node node, String member) {
        return node.findCompilationUnit().flatMap(cu -> {
            for (ImportDeclaration i : cu.getImports()) {
                if (!i.isStatic()) {
                    continue;
                }
                String name = i.getNameAsString();
                if (!i.isAsterisk() && name.endsWith("." + member)) {
                    return Optional.of(name.substring(0, name.length() - member.length() - 1));
                }
            }
            for (ImportDeclaration i : cu.getImports()) {
                if (i.isStatic() && i.isAsterisk()) {
                    return Optional.of(i.getNameAsString());
                }
            }
            return Optional.empty();
        });
    }

    private static String code(Node node) {
        String text = node.toString().replaceAll("\\s+", " ");
        return text.length() > 160 ? text.substring(0, 157) + "..." : text;
    }
}

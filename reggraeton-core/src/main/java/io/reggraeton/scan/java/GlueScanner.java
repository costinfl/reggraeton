package io.reggraeton.scan.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.UnknownType;
import io.reggraeton.model.DataTableTypeDefinition;
import io.reggraeton.model.Finding;
import io.reggraeton.model.Hook;
import io.reggraeton.model.Parameter;
import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.StepDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Finds Cucumber glue in the parsed sources: annotated step definitions and hooks, cucumber-java8
 * lambda registrations, custom parameter types and data-table/doc-string transformers.
 */
public final class GlueScanner {

    /** Everything found, plus the AST node holding the body of each step definition / hook. */
    public record Glue(
            List<StepDefinition> stepDefinitions,
            List<Hook> hooks,
            List<ParameterTypeDefinition> parameterTypes,
            List<DataTableTypeDefinition> dataTableTypes,
            Map<String, Node> bodies,
            List<Finding> findings) {
    }

    private final JavaSourceIndex index;
    private final List<StepDefinition> steps = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();
    private final List<ParameterTypeDefinition> parameterTypes = new ArrayList<>();
    private final List<DataTableTypeDefinition> transformers = new ArrayList<>();
    private final Map<String, Node> bodies = new LinkedHashMap<>();
    private final List<Finding> findings = new ArrayList<>();

    public GlueScanner(JavaSourceIndex index) {
        this.index = index;
    }

    public Glue scan() {
        for (TypeDeclaration<?> type : index.types().values()) {
            for (MethodDeclaration method : type.getMethods()) {
                scanMethod(type, method);
            }
            if (usesLambdaGlue(type)) {
                type.findAll(MethodCallExpr.class, call -> belongsTo(call, type)).forEach(call -> scanLambdaGlue(type, call));
            }
        }
        return new Glue(steps, hooks, parameterTypes, transformers, bodies, findings);
    }

    // ----------------------------------------------------------------------------------------
    // Annotation style
    // ----------------------------------------------------------------------------------------

    private void scanMethod(TypeDeclaration<?> type, MethodDeclaration method) {
        String className = JavaSourceIndex.qualifiedName(type);
        String methodId = JavaSourceIndex.callableId(method);
        String file = index.pathOf(method);
        List<Parameter> params = method.getParameters().stream()
                .map(p -> new Parameter(p.getNameAsString(), p.getType().asString() + (p.isVarArgs() ? "..." : "")))
                .collect(Collectors.toList());

        List<AnnotationExpr> stepAnnotations = new ArrayList<>();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String qualified = Expressions.qualifiedAnnotationName(annotation);
            if (GlueAnnotations.isStepAnnotation(qualified)
                    || (!qualified.contains(".") && GlueAnnotations.isUnqualifiedStepKeyword(qualified))) {
                stepAnnotations.add(annotation);
                continue;
            }
            String phase = GlueAnnotations.hookPhase(qualified);
            if (phase != null) {
                List<String> tags = Expressions.member(annotation, "value").map(Expressions::constantStrings).orElse(List.of());
                Integer order = Expressions.member(annotation, "order").map(Expressions::constantInt).orElse(null);
                hooks.add(new Hook(methodId, phase, String.join(" and ", tags),
                        order == null ? Hook.DEFAULT_ORDER : order, StepDefinition.ANNOTATION,
                        className, method.getNameAsString(), params, file, JavaSourceIndex.lineOf(annotation)));
                bodies.put(methodId, method);
                continue;
            }
            if (GlueAnnotations.isParameterType(qualified)) {
                List<String> regexps = Expressions.member(annotation, "value").map(Expressions::constantStrings).orElse(List.of());
                String name = Expressions.member(annotation, "name").map(Expressions::constantString)
                        .filter(n -> !n.isEmpty()).orElse(method.getNameAsString());
                parameterTypes.add(new ParameterTypeDefinition(name, regexps, className, method.getNameAsString(),
                        method.getType().asString(), file, JavaSourceIndex.lineOf(method)));
                continue;
            }
            String transformer = GlueAnnotations.transformerKind(qualified);
            if (transformer != null) {
                transformers.add(new DataTableTypeDefinition(transformer, className, method.getNameAsString(),
                        method.getType().asString(), file, JavaSourceIndex.lineOf(method)));
            }
        }

        for (int i = 0; i < stepAnnotations.size(); i++) {
            AnnotationExpr annotation = stepAnnotations.get(i);
            String id = stepAnnotations.size() == 1 ? methodId : methodId + "[" + (i + 1) + "]";
            Expression value = Expressions.member(annotation, "value").orElse(null);
            String expression = Expressions.constantString(value);
            int line = JavaSourceIndex.lineOf(annotation);
            if (expression == null) {
                findings.add(Finding.warning("NON_CONSTANT_STEP_PATTERN",
                        "Step pattern is not a compile-time constant reggraeton can evaluate: " + value, file, line));
                continue;
            }
            String keyword = GlueAnnotations.keyword(Expressions.qualifiedAnnotationName(annotation));
            steps.add(new StepDefinition(id, keyword, expression, expressionType(expression),
                    StepDefinition.ANNOTATION, className, method.getNameAsString(), params, file, line));
            bodies.put(id, method);
        }
    }

    // ----------------------------------------------------------------------------------------
    // cucumber-java8 lambda style: Given("...", (String s) -> {...}) inside the constructor
    // ----------------------------------------------------------------------------------------

    private static boolean usesLambdaGlue(TypeDeclaration<?> type) {
        return type.findCompilationUnit()
                .map(cu -> cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("io.cucumber.java8")
                        || i.getNameAsString().startsWith("cucumber.api.java8")))
                .orElse(false);
    }

    /** True if {@code call} is lexically inside {@code type} and not inside a nested type. */
    private static boolean belongsTo(MethodCallExpr call, TypeDeclaration<?> type) {
        return call.findAncestor(TypeDeclaration.class).map(t -> t == type).orElse(false);
    }

    private void scanLambdaGlue(TypeDeclaration<?> type, MethodCallExpr call) {
        if (call.getScope().isPresent() && !(call.getScope().get() instanceof ThisExpr)) {
            return;
        }
        if (call.getArguments().isEmpty()) {
            return;
        }
        Expression last = call.getArgument(call.getArguments().size() - 1);
        if (!(last instanceof LambdaExpr) && !(last instanceof MethodReferenceExpr)) {
            return;
        }
        String name = call.getNameAsString();
        String className = JavaSourceIndex.qualifiedName(type);
        String file = index.pathOf(call);
        int line = JavaSourceIndex.lineOf(call);
        List<Parameter> params = last instanceof LambdaExpr lambda
                ? lambda.getParameters().stream()
                        .map(p -> new Parameter(p.getNameAsString(), p.getType() instanceof UnknownType ? "?" : p.getType().asString()))
                        .collect(Collectors.toList())
                : List.of();

        if (GlueAnnotations.LAMBDA_STEP_METHODS.contains(name)) {
            String expression = Expressions.constantString(call.getArgument(0));
            if (expression == null) {
                findings.add(Finding.warning("NON_CONSTANT_STEP_PATTERN",
                        "Step pattern is not a compile-time constant reggraeton can evaluate: " + call.getArgument(0), file, line));
                return;
            }
            String id = className + "#" + name + "@L" + line;
            steps.add(new StepDefinition(id, name, expression, expressionType(expression), StepDefinition.LAMBDA,
                    className, null, params, file, line));
            bodies.put(id, last);
        } else if (GlueAnnotations.HOOK_PHASES.contains(name)) {
            String tags = "";
            int order = Hook.DEFAULT_ORDER;
            for (int i = 0; i < call.getArguments().size() - 1; i++) {
                Expression arg = call.getArgument(i);
                String s = Expressions.constantString(arg);
                Integer n = Expressions.constantInt(arg);
                if (s != null) {
                    tags = s;
                } else if (n != null) {
                    order = n;
                }
            }
            String id = className + "#" + name + "@L" + line;
            hooks.add(new Hook(id, name, tags, order, StepDefinition.LAMBDA, className, null, params, file, line));
            bodies.put(id, last);
        } else if (name.equals("ParameterType") && call.getArguments().size() >= 3) {
            String typeName = Expressions.constantString(call.getArgument(0));
            String regex = Expressions.constantString(call.getArgument(1));
            if (typeName != null && regex != null) {
                parameterTypes.add(new ParameterTypeDefinition(typeName, List.of(regex), className, null, "?", file, line));
            }
        } else if (name.equals("DataTableType") || name.equals("DocStringType")) {
            transformers.add(new DataTableTypeDefinition(name, className, null, "?", file, line));
        }
    }

    // ----------------------------------------------------------------------------------------

    /**
     * Cucumber-JVM's rule: a pattern is a regular expression when it starts with {@code ^}, ends
     * with {@code $}, or is wrapped in {@code /.../}; otherwise it is a Cucumber Expression.
     */
    public static String expressionType(String expression) {
        boolean regex = expression.startsWith("^") || expression.endsWith("$")
                || (expression.length() > 1 && expression.startsWith("/") && expression.endsWith("/"));
        return regex ? StepDefinition.REGULAR_EXPRESSION : StepDefinition.CUCUMBER_EXPRESSION;
    }
}

package io.reggraeton.model;

import java.util.List;

/**
 * A Cucumber step definition found in the glue code.
 *
 * @param id             call-graph node id of the definition (method id, or {@code Class#step@line} for lambdas)
 * @param keyword        annotation / method keyword used ({@code Given}, {@code When}, ...)
 * @param expression     the step pattern as written in the source
 * @param expressionType {@link #CUCUMBER_EXPRESSION} or {@link #REGULAR_EXPRESSION}
 * @param style          {@link #ANNOTATION} or {@link #LAMBDA}
 * @param methodName     Java method name, null for lambda definitions
 */
public record StepDefinition(
        String id,
        String keyword,
        String expression,
        String expressionType,
        String style,
        String className,
        String methodName,
        List<Parameter> parameters,
        String file,
        int line) {

    public static final String CUCUMBER_EXPRESSION = "cucumber-expression";
    public static final String REGULAR_EXPRESSION = "regular-expression";
    public static final String ANNOTATION = "annotation";
    public static final String LAMBDA = "lambda";
}

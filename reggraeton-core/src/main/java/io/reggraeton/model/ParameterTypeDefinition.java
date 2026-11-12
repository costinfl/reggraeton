package io.reggraeton.model;

import java.util.List;

/** A custom Cucumber parameter type ({@code @ParameterType} method or java8 {@code ParameterType(...)}). */
public record ParameterTypeDefinition(
        String name,
        List<String> regexps,
        String className,
        String methodName,
        String returnType,
        String file,
        int line) {
}

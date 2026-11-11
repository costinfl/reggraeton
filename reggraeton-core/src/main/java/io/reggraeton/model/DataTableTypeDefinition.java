package io.reggraeton.model;

/** A custom {@code @DataTableType} / {@code @DocStringType} conversion method. */
public record DataTableTypeDefinition(
        String kind,
        String className,
        String methodName,
        String returnType,
        String file,
        int line) {
}

package io.reggraeton.model;

/**
 * A step as written in a feature file (may still contain {@code <placeholders>}).
 *
 * @param keyword     keyword as written, trimmed ({@code Given}, {@code And}, {@code *} ...)
 * @param keywordType Gherkin keyword type: {@code Context}, {@code Action}, {@code Outcome}, {@code Conjunction} or {@code Unknown}
 */
public record GherkinStep(String keyword, String keywordType, String text, int line, StepArgument argument) {
}

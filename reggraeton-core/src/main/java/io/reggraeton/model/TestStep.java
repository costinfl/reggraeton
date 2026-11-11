package io.reggraeton.model;

/**
 * A step of a {@link TestCase}, with placeholders already substituted.
 *
 * @param keyword        keyword as written ({@code And}, {@code But}, {@code *} included)
 * @param type           effective type resolved by Cucumber: {@code Context}, {@code Action}, {@code Outcome} or {@code Unknown}
 * @param line           line of the step in the feature file
 * @param fromBackground true when the step comes from a Background
 */
public record TestStep(
        String keyword,
        String type,
        String text,
        int line,
        boolean fromBackground,
        StepArgument argument) {

    /** Gherkin keyword to use when the step is rendered on its own ({@code Given}/{@code When}/{@code Then}). */
    public String effectiveKeyword() {
        return switch (type == null ? "" : type) {
            case "Context" -> "Given";
            case "Action" -> "When";
            case "Outcome" -> "Then";
            default -> keyword;
        };
    }
}

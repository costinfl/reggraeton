package io.reggraeton.model;

import java.util.List;

/**
 * A Scenario or Scenario Outline as written in a feature file.
 *
 * @param rule        name of the enclosing {@code Rule:}, or null
 * @param tags        tags written directly on the scenario (not inherited ones)
 * @param background  steps of the Rule-level background that applies to this scenario (feature background is on {@link FeatureFile})
 */
public record ScenarioDefinition(
        String keyword,
        String name,
        int line,
        String rule,
        List<String> tags,
        boolean outline,
        List<GherkinStep> background,
        List<GherkinStep> steps,
        List<ExamplesBlock> examples) {

    /** One {@code Examples:} block of an outline. */
    public record ExamplesBlock(String name, int line, List<String> tags, List<String> header, List<ExampleRow> rows) {
    }

    /** One data row of an {@code Examples:} block. */
    public record ExampleRow(int line, List<String> values) {
    }
}

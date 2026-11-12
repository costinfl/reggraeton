package io.reggraeton.model;

import java.util.List;

/**
 * One executable test: a Scenario, or one Examples row of a Scenario Outline, exactly as
 * Cucumber would run it (backgrounds prepended, placeholders substituted, tags inherited).
 * Every test case gets exactly one generated Playwright test.
 *
 * @param id            stable identifier {@code <feature path>:<line>}, where line is the scenario line
 *                      or, for outline rows, the Examples row line (the same form Cucumber accepts to run a single test)
 * @param name          test name as Cucumber reports it (outline rows keep the placeholder-substituted name)
 * @param exampleLine   line of the Examples row, null for plain scenarios
 * @param exampleIndex  1-based position of the row across all Examples blocks of the outline, null for plain scenarios
 * @param tags          effective tags (feature + rule + scenario + examples)
 */
public record TestCase(
        String id,
        String feature,
        String featureName,
        String rule,
        String scenarioName,
        String name,
        int scenarioLine,
        Integer exampleLine,
        Integer exampleIndex,
        String examplesName,
        List<String> tags,
        List<TestStep> steps) {

    public TestCase withSteps(List<TestStep> newSteps) {
        return new TestCase(id, feature, featureName, rule, scenarioName, name, scenarioLine, exampleLine,
                exampleIndex, examplesName, tags, newSteps);
    }
}

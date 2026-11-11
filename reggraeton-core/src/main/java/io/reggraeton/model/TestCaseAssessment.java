package io.reggraeton.model;

import java.util.List;

/**
 * Conversion readiness of one {@link TestCase}.
 *
 * @param status            {@link #READY} (everything reachable has a translation rule), {@link #PARTIAL} (some
 *                          code will become TODO stubs) or {@link #BLOCKED} (undefined / ambiguous steps)
 * @param stepDefinitionIds step definitions used, in execution order (duplicates removed)
 * @param hookIds           hooks that apply to the test case (tag expressions evaluated)
 * @param reachableMethods  number of project methods reachable from those step definitions and hooks
 * @param manualItems       API call sites in reachable code that have no translation rule
 */
public record TestCaseAssessment(
        String testCaseId,
        String status,
        List<String> stepDefinitionIds,
        List<String> hookIds,
        int reachableMethods,
        int manualItems,
        List<String> issues) {

    public static final String READY = "ready";
    public static final String PARTIAL = "partial";
    public static final String BLOCKED = "blocked";
}

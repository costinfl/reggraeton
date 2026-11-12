package io.reggraeton.model;

import java.util.List;

/**
 * How a {@link TestStep} matched the glue code.
 *
 * @param status            {@link #MATCHED}, {@link #UNDEFINED} or {@link #AMBIGUOUS}
 * @param stepDefinitionIds ids of all matching definitions (exactly one when matched)
 * @param arguments         arguments extracted for the (single) matching definition
 */
public record StepMatch(String status, List<String> stepDefinitionIds, List<MatchedArgument> arguments) {

    public static final String MATCHED = "matched";
    public static final String UNDEFINED = "undefined";
    public static final String AMBIGUOUS = "ambiguous";

    /**
     * @param group         raw text captured from the step
     * @param value         value after Cucumber's built-in transformation ({@code {string}} without quotes, ...)
     * @param parameterType Cucumber parameter type name; empty for anonymous / regex groups
     * @param start         offset of the group in the step text, -1 for an optional group that did not match
     */
    public record MatchedArgument(String group, String value, String parameterType, int start) {
    }
}

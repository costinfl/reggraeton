package io.reggraeton.model;

import java.util.List;

/**
 * Everything {@code reggraeton assess} discovered in a repository. Serialised as {@code inventory.json};
 * it is also the input contract of {@code reggraeton convert}.
 */
public record Inventory(
        String schemaVersion,
        String generatedAt,
        ProjectLayout layout,
        List<FeatureFile> features,
        List<TestCase> testCases,
        List<StepDefinition> stepDefinitions,
        List<Hook> hooks,
        List<ParameterTypeDefinition> parameterTypes,
        List<DataTableTypeDefinition> dataTableTypes,
        CallGraph callGraph,
        List<TestCaseAssessment> assessments,
        List<Finding> findings) {

    public static final String SCHEMA_VERSION = "1";
}

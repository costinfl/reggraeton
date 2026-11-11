package io.reggraeton.model;

import java.util.List;

/**
 * A parsed {@code .feature} file, as written (before Cucumber expands outlines).
 *
 * @param path        repo-relative path, {@code /}-separated
 * @param parseError  non-null when the file could not be parsed; the other lists are then empty
 */
public record FeatureFile(
        String path,
        String language,
        String keyword,
        String name,
        String description,
        int line,
        List<String> tags,
        List<GherkinStep> background,
        List<ScenarioDefinition> scenarios,
        String parseError) {
}

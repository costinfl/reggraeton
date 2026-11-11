package io.reggraeton.scan;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTag;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;
import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.GherkinStep;
import io.reggraeton.model.ScenarioDefinition;
import io.reggraeton.model.ScenarioDefinition.ExampleRow;
import io.reggraeton.model.ScenarioDefinition.ExamplesBlock;
import io.reggraeton.model.StepArgument;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestStep;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses {@code .feature} files with the official Cucumber Gherkin parser and compiles them into
 * {@link TestCase}s with Cucumber's own pickle compiler, so outline expansion, background
 * inlining and tag inheritance are exactly what Cucumber does at run time.
 */
public final class FeatureParser {

    /** Result of parsing one file. */
    public record Parsed(FeatureFile feature, List<TestCase> testCases) {
    }

    private final GherkinParser parser = GherkinParser.builder()
            .includeSource(false)
            .includeGherkinDocument(true)
            .includePickles(true)
            .build();

    /**
     * @param file         the feature file
     * @param relativePath repo-relative, {@code /}-separated path used as URI and in ids
     */
    public Parsed parse(Path file, String relativePath) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Envelope> envelopes = parser.parse(relativePath, bytes).collect(Collectors.toList());

        List<String> errors = envelopes.stream()
                .map(Envelope::getParseError).flatMap(java.util.Optional::stream)
                .map(FeatureParser::describe)
                .collect(Collectors.toList());
        GherkinDocument document = envelopes.stream()
                .map(Envelope::getGherkinDocument).flatMap(java.util.Optional::stream)
                .findFirst().orElse(null);

        if (!errors.isEmpty() || document == null || document.getFeature().isEmpty()) {
            String error = errors.isEmpty() ? "Empty feature file (no Feature: keyword)" : String.join("; ", errors);
            FeatureFile broken = new FeatureFile(relativePath, null, null, null, null, 0,
                    List.of(), List.of(), List.of(), error);
            return new Parsed(broken, List.of());
        }

        Feature feature = document.getFeature().get();
        Index index = new Index();
        FeatureFile featureFile = buildFeature(relativePath, feature, index);

        List<TestCase> testCases = new ArrayList<>();
        for (Envelope envelope : envelopes) {
            envelope.getPickle().ifPresent(p -> testCases.add(toTestCase(p, featureFile, index)));
        }
        return new Parsed(featureFile, testCases);
    }

    private static String describe(ParseError error) {
        String where = error.getSource().getLocation()
                .map(l -> "line " + l.getLine() + ": ")
                .orElse("");
        return where + error.getMessage();
    }

    // ----------------------------------------------------------------------------------------
    // AST -> model, while indexing ids so pickles can be traced back to their source lines.
    // ----------------------------------------------------------------------------------------

    private static final class Index {
        final Map<String, Step> steps = new HashMap<>();
        final Set<String> backgroundStepIds = new HashSet<>();
        final Map<String, ScenarioInfo> scenarios = new HashMap<>();
        final Map<String, RowInfo> exampleRows = new HashMap<>();
    }

    private record ScenarioInfo(String name, int line, String rule) {
    }

    private record RowInfo(int line, int indexInOutline, String examplesName) {
    }

    private FeatureFile buildFeature(String path, Feature feature, Index index) {
        List<GherkinStep> background = List.of();
        List<ScenarioDefinition> scenarios = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            if (child.getBackground().isPresent()) {
                background = backgroundSteps(child.getBackground().get(), index);
            }
            child.getScenario().ifPresent(s -> scenarios.add(scenario(s, null, List.of(), index)));
            child.getRule().ifPresent(r -> scenarios.addAll(rule(r, index)));
        }
        return new FeatureFile(
                path,
                feature.getLanguage(),
                feature.getKeyword(),
                feature.getName(),
                trimDescription(feature.getDescription()),
                feature.getLocation().getLine().intValue(),
                tags(feature.getTags()),
                background,
                scenarios,
                null);
    }

    private List<ScenarioDefinition> rule(Rule rule, Index index) {
        List<GherkinStep> background = List.of();
        List<ScenarioDefinition> scenarios = new ArrayList<>();
        for (RuleChild child : rule.getChildren()) {
            if (child.getBackground().isPresent()) {
                background = backgroundSteps(child.getBackground().get(), index);
            }
            List<GherkinStep> bg = background;
            child.getScenario().ifPresent(s -> scenarios.add(scenario(s, rule.getName(), bg, index)));
        }
        return scenarios;
    }

    private List<GherkinStep> backgroundSteps(Background background, Index index) {
        background.getSteps().forEach(s -> index.backgroundStepIds.add(s.getId()));
        return steps(background.getSteps(), index);
    }

    private ScenarioDefinition scenario(Scenario s, String rule, List<GherkinStep> ruleBackground, Index index) {
        int line = s.getLocation().getLine().intValue();
        index.scenarios.put(s.getId(), new ScenarioInfo(s.getName(), line, rule));

        List<ExamplesBlock> examples = new ArrayList<>();
        int rowCounter = 0;
        for (Examples ex : s.getExamples()) {
            List<String> header = ex.getTableHeader().map(FeatureParser::cells).orElse(List.of());
            List<ExampleRow> rows = new ArrayList<>();
            for (TableRow row : ex.getTableBody()) {
                rowCounter++;
                int rowLine = row.getLocation().getLine().intValue();
                index.exampleRows.put(row.getId(), new RowInfo(rowLine, rowCounter, blankToNull(ex.getName())));
                rows.add(new ExampleRow(rowLine, cells(row)));
            }
            examples.add(new ExamplesBlock(blankToNull(ex.getName()), ex.getLocation().getLine().intValue(),
                    tags(ex.getTags()), header, rows));
        }
        return new ScenarioDefinition(
                s.getKeyword().trim(),
                s.getName(),
                line,
                rule,
                tags(s.getTags()),
                !s.getExamples().isEmpty(),
                ruleBackground,
                steps(s.getSteps(), index),
                examples);
    }

    private List<GherkinStep> steps(List<Step> steps, Index index) {
        List<GherkinStep> result = new ArrayList<>();
        for (Step step : steps) {
            index.steps.put(step.getId(), step);
            StepArgument argument = null;
            if (step.getDataTable().isPresent()) {
                argument = StepArgument.dataTable(step.getDataTable().get().getRows().stream()
                        .map(FeatureParser::cells).collect(Collectors.toList()));
            } else if (step.getDocString().isPresent()) {
                var doc = step.getDocString().get();
                argument = StepArgument.docString(doc.getContent(), doc.getMediaType().orElse(null));
            }
            result.add(new GherkinStep(
                    step.getKeyword().trim(),
                    step.getKeywordType().map(t -> t.value()).orElse("Unknown"),
                    step.getText(),
                    step.getLocation().getLine().intValue(),
                    argument));
        }
        return result;
    }

    // ----------------------------------------------------------------------------------------
    // Pickle -> TestCase
    // ----------------------------------------------------------------------------------------

    private TestCase toTestCase(Pickle pickle, FeatureFile feature, Index index) {
        List<String> nodeIds = pickle.getAstNodeIds();
        ScenarioInfo scenario = index.scenarios.get(nodeIds.get(0));
        RowInfo row = nodeIds.size() > 1 ? index.exampleRows.get(nodeIds.get(nodeIds.size() - 1)) : null;

        List<TestStep> steps = new ArrayList<>();
        for (PickleStep ps : pickle.getSteps()) {
            String stepId = ps.getAstNodeIds().get(0);
            Step source = index.steps.get(stepId);
            steps.add(new TestStep(
                    source == null ? "*" : source.getKeyword().trim(),
                    ps.getType().map(t -> t.value()).orElse("Unknown"),
                    ps.getText(),
                    source == null ? 0 : source.getLocation().getLine().intValue(),
                    index.backgroundStepIds.contains(stepId),
                    argument(ps.getArgument().orElse(null))));
        }

        int idLine = row != null ? row.line() : scenario.line();
        return new TestCase(
                feature.path() + ":" + idLine,
                feature.path(),
                feature.name(),
                scenario.rule(),
                scenario.name(),
                pickle.getName(),
                scenario.line(),
                row == null ? null : row.line(),
                row == null ? null : row.indexInOutline(),
                row == null ? null : row.examplesName(),
                pickle.getTags().stream().map(PickleTag::getName).collect(Collectors.toList()),
                steps);
    }

    private static StepArgument argument(PickleStepArgument argument) {
        if (argument == null) {
            return null;
        }
        if (argument.getDataTable().isPresent()) {
            return StepArgument.dataTable(argument.getDataTable().get().getRows().stream()
                    .map(r -> r.getCells().stream().map(c -> c.getValue()).collect(Collectors.toList()))
                    .collect(Collectors.toList()));
        }
        if (argument.getDocString().isPresent()) {
            var doc = argument.getDocString().get();
            return StepArgument.docString(doc.getContent(), doc.getMediaType().orElse(null));
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------

    private static List<String> cells(TableRow row) {
        return row.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
    }

    private static List<String> tags(List<Tag> tags) {
        return tags.stream().map(Tag::getName).collect(Collectors.toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String trimDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.lines().map(String::trim).collect(Collectors.joining("\n")).trim();
    }
}

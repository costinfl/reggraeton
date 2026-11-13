package io.reggraeton.scan;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Finding;
import io.reggraeton.model.Hook;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.ProjectLayout;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;
import io.reggraeton.scan.java.CallGraphBuilder;
import io.reggraeton.scan.java.ExternalPolicy;
import io.reggraeton.scan.java.GlueScanner;
import io.reggraeton.scan.java.JavaSourceIndex;
import io.reggraeton.util.RepoFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entry point of the discovery phase: scans a repository read-only and builds the {@link Inventory}
 * (features, test cases, glue, step matches, call graph, readiness and findings).
 */
public final class RepositoryScanner {

    private static final List<String> PHASE_ORDER = List.of(
            "BeforeAll", "Before", "BeforeStep", "AfterStep", "After", "AfterAll");

    private final List<Path> extraClasspath;
    private final Path configFile;

    public RepositoryScanner() {
        this(List.of());
    }

    /** @param extraClasspath jars of the scanned project, used only to improve type resolution */
    public RepositoryScanner(List<Path> extraClasspath) {
        this(extraClasspath, null);
    }

    /**
     * @param configFile {@code reggraeton.properties} to use; null means the one in the repository root, if any
     */
    public RepositoryScanner(List<Path> extraClasspath, Path configFile) {
        this.extraClasspath = List.copyOf(extraClasspath);
        this.configFile = configFile;
    }

    /** Inventory plus the parsed Java sources (needed by the translator). */
    public record Scan(Inventory inventory, JavaSourceIndex index) {
    }

    public Inventory scan(Path repository) {
        return scanWithSources(repository).inventory();
    }

    public Scan scanWithSources(Path repository) {
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        List<Finding> findings = new ArrayList<>();
        ProjectLayout layout = new ProjectLayoutDetector().detect(root);

        // 1. features -> test cases
        FeatureParser featureParser = new FeatureParser();
        List<FeatureFile> features = new ArrayList<>();
        List<TestCase> testCases = new ArrayList<>();
        for (Path file : RepoFiles.findByExtension(root, ".feature")) {
            FeatureParser.Parsed parsed = featureParser.parse(file, RepoFiles.relative(root, file));
            features.add(parsed.feature());
            testCases.addAll(parsed.testCases());
            if (parsed.feature().parseError() != null) {
                findings.add(Finding.error("FEATURE_PARSE_ERROR", parsed.feature().parseError(), parsed.feature().path(), 0));
            }
        }

        // 2. glue code
        Path config = configFile != null ? configFile : root.resolve(ExternalPolicy.FILE_NAME);
        if (configFile != null && !Files.isRegularFile(configFile)) {
            throw new IllegalArgumentException("Configuration file not found: " + configFile);
        }
        ExternalPolicy policy = ExternalPolicy.load(config);
        if (policy.source() != null) {
            findings.add(Finding.info("CONFIGURATION", "External library rules read from " + policy.source(), null, 0));
        }
        JavaSourceIndex index = JavaSourceIndex.build(root, extraClasspath, policy);
        findings.addAll(index.problems());
        GlueScanner.Glue glue = new GlueScanner(index).scan();
        findings.addAll(glue.findings());

        List<StepDefinition> stepDefinitions = inGlue(glue.stepDefinitions(), StepDefinition::className, layout, findings,
                d -> d.file(), d -> d.line(), "step definition");
        List<Hook> hooks = inGlue(glue.hooks(), Hook::className, layout, findings, Hook::file, Hook::line, "hook");
        List<ParameterTypeDefinition> parameterTypes = inGlue(glue.parameterTypes(), ParameterTypeDefinition::className,
                layout, findings, ParameterTypeDefinition::file, ParameterTypeDefinition::line, "parameter type");
        hooks.sort(Comparator.comparingInt((Hook h) -> PHASE_ORDER.indexOf(h.phase())).thenComparingInt(Hook::order));

        // 3. step matching
        StepMatcher matcher = new StepMatcher(stepDefinitions, parameterTypes);
        findings.addAll(matcher.findings());
        List<TestCase> matched = new ArrayList<>();
        for (TestCase testCase : testCases) {
            List<TestStep> steps = testCase.steps().stream()
                    .map(s -> s.withMatch(matcher.match(s.text())))
                    .collect(Collectors.toList());
            matched.add(testCase.withSteps(steps));
        }

        // 4. call graph + readiness
        CallGraph graph = new CallGraphBuilder(index, glue).build();
        Assessor assessor = new Assessor(graph);
        List<TestCaseAssessment> assessments = matched.stream()
                .map(tc -> assessor.assess(tc, hooks))
                .collect(Collectors.toList());
        Set<String> usedNodes = new LinkedHashSet<>();
        for (TestCaseAssessment a : assessments) {
            a.stepDefinitionIds().forEach(id -> usedNodes.addAll(assessor.reachable(id)));
            a.hookIds().forEach(id -> usedNodes.addAll(assessor.reachable(id)));
        }
        hooks.stream().filter(h -> h.phase().endsWith("All")).forEach(h -> usedNodes.addAll(assessor.reachable(h.id())));
        findings.addAll(Assessor.findings(layout, matched, stepDefinitions, hooks, graph, usedNodes));

        return new Scan(new Inventory(
                Inventory.SCHEMA_VERSION,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString(),
                layout,
                features,
                matched,
                stepDefinitions,
                hooks,
                parameterTypes,
                glue.dataTableTypes(),
                graph,
                assessments,
                findings), index);
    }

    /**
     * Cucumber only loads glue from the configured glue packages. When the project declares glue,
     * definitions outside it are dropped (and reported), exactly as Cucumber would ignore them.
     */
    private static <T> List<T> inGlue(List<T> items, java.util.function.Function<T, String> className,
                                      ProjectLayout layout, List<Finding> findings,
                                      java.util.function.Function<T, String> file,
                                      java.util.function.ToIntFunction<T> line, String what) {
        if (layout.gluePackages().isEmpty()) {
            return new ArrayList<>(items);
        }
        List<T> kept = new ArrayList<>();
        for (T item : items) {
            String cls = className.apply(item);
            boolean inGlue = layout.gluePackages().stream().anyMatch(p -> cls.equals(p) || cls.startsWith(p + "."));
            if (inGlue) {
                kept.add(item);
            } else {
                findings.add(Finding.info("OUTSIDE_GLUE", "The " + what + " in " + cls
                        + " is outside the configured glue packages " + layout.gluePackages() + " and is ignored, as Cucumber would",
                        file.apply(item), line.applyAsInt(item)));
            }
        }
        return kept;
    }
}

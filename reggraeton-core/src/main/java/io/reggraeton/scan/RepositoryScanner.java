package io.reggraeton.scan;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.DatabaseInventory;
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
import io.reggraeton.scan.db.DatabaseAccess;
import io.reggraeton.scan.db.DatabaseScanner;
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
        for (io.reggraeton.scan.java.DriverWrappers.Wrapper w : io.reggraeton.scan.java.DriverWrappers.find(index).all().values()) {
            var type = index.types().get(w.className());
            String file = index.pathOf(type);
            int line = io.reggraeton.scan.java.JavaSourceIndex.lineOf(type);
            String supertypes = w.seleniumSupertypes().stream().map(io.reggraeton.scan.java.JavaSourceIndex::withoutPackage)
                    .collect(java.util.stream.Collectors.joining(", "));
            if (w.extendsSeleniumClass()) {
                findings.add(Finding.warning("DRIVER_SUBCLASS", w.className() + " extends " + supertypes
                        + ": the Selenium superclass is removed, so methods it inherited from it must be rewritten by hand",
                        file, line));
            } else {
                findings.add(Finding.info("DRIVER_WRAPPER", w.className() + " implements " + supertypes
                        + ": converted to a plain class with a page() method; its methods "
                        + (w.redirected().isEmpty() ? "" : "returning Selenium-only types (" + String.join(", ", w.redirected())
                        + ") are removed and their callers use page(); the others ") + "keep their custom logic", file, line));
            }
        }
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

        // 5. database access
        DatabaseInventory database = DatabaseScanner.scan(root, graph);
        findings.addAll(databaseFindings(hooks, stepDefinitions, graph, assessor, database));

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
                findings,
                database), index);
    }

    /**
     * Hooks that reach the database usually prepare or clean up test data: they decide whether a run
     * can leave the database changed, so each is reported.
     */
    private static List<Finding> databaseFindings(List<Hook> hooks, List<StepDefinition> steps, CallGraph graph,
                                                  Assessor assessor, DatabaseInventory database) {
        List<Finding> findings = new ArrayList<>();
        DatabaseAccess access = new DatabaseAccess(graph);
        for (Hook h : hooks) {
            DatabaseAccess.Reach reach = access.reach(assessor.reachable(h.id()));
            if (reach.isEmpty()) {
                continue;
            }
            String tags = h.tagExpression().isBlank() ? "every scenario" : "scenarios tagged " + h.tagExpression();
            findings.add(Finding.warning("DATABASE_HOOK", "@" + h.phase() + " hook " + shortId(h.id())
                    + " (" + tags + ") reaches the database: " + reach.summary()
                    + (reach.writes() ? ". It changes data, so it is test data setup or cleanup" : ""), h.file(), h.line()));
        }
        long dbSteps = steps.stream().filter(s -> !access.reach(assessor.reachable(s.id())).isEmpty()).count();
        if (dbSteps > 0) {
            findings.add(Finding.info("DATABASE_STEPS", dbSteps + " step definition(s) reach the database; see the Database access section",
                    null, 0));
        }
        if (!database.connections().isEmpty()) {
            findings.add(Finding.info("DATABASE_CONNECTIONS", database.connections().size()
                    + " database connection string(s) or setting(s) found; see the Database access section", null, 0));
        }
        return findings;
    }

    private static String shortId(String id) {
        String cls = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
        return cls.substring(cls.lastIndexOf('.') + 1) + (id.contains("#") ? id.substring(id.indexOf('#')) : "");
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

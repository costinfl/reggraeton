package io.reggraeton.generate;

import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Hook;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.report.ReportData;
import io.reggraeton.report.Traceability;
import io.reggraeton.scan.Assessor;
import io.reggraeton.scan.RepositoryScanner;
import io.reggraeton.translate.RuntimeSources;
import io.reggraeton.translate.SupportNames;
import io.reggraeton.translate.TranslatedSource;
import io.reggraeton.translate.Translator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code reggraeton convert}: writes a standalone Maven project with one Playwright test per
 * existing scenario / Examples row.
 */
public final class ProjectGenerator {

    /**
     * @param files            files written (relative to the output directory)
     * @param testCases        test cases in the legacy suite
     * @param generatedTests   tests generated for them
     * @param missingTestCases test case ids without a generated test (must be empty: completeness gate)
     * @param todos            places left for manual conversion
     * @param problems         other conversion problems
     */
    public record Result(Path output, List<String> files, int testCases, int generatedTests,
                         List<String> missingTestCases, List<TranslatedSource.Todo> todos, List<String> problems,
                         int blockedTests) {

        public boolean complete() {
            return missingTestCases.isEmpty();
        }
    }

    private final RepositoryScanner.Scan scan;
    private final ConvertOptions options;
    private final Map<String, String> files = new TreeMap<>();
    private final List<String> problems = new ArrayList<>();

    public ProjectGenerator(RepositoryScanner.Scan scan, ConvertOptions options) {
        this.scan = scan;
        this.options = options;
    }

    public Result generate() {
        Inventory inventory = scan.inventory();
        TestNaming naming = new TestNaming(inventory, options.basePackage());
        Set<String> used = Assessor.usedNodes(inventory);
        Translator.Result translation = Translator.translate(scan.index(), inventory, used, options.supportPackage());
        String supportDir = "src/test/java/" + options.supportPackage().replace('.', '/') + "/";
        String javaDir = "src/test/java/";

        Set<String> generatedIds = new LinkedHashSet<>();
        boolean lambdaGlue = inventory.stepDefinitions().stream().anyMatch(s -> StepDefinition.LAMBDA.equals(s.style()))
                || inventory.hooks().stream().anyMatch(h -> h.methodName() == null);

        Set<String> runtimeClasses = new TreeSet<>(SupportNames.RUNTIME_CLASSES);
        if (options.mode() == ConvertOptions.Mode.BDD) {
            runtimeClasses.addAll(SupportNames.BDD_CLASSES);
            translation.sources().forEach(s -> files.put(javaDir + s.relativePath(), s.source()));
            generatedIds.addAll(writeBdd(inventory, naming));
        } else {
            runtimeClasses.addAll(SupportNames.NATIVE_CLASSES);
            NativeGlueRewriter rewriter = new NativeGlueRewriter(inventory, options.supportPackage());
            NativeGlueRewriter.Result glue = rewriter.rewrite(translation.sources());
            problems.addAll(glue.problems());
            glue.sources().forEach((path, source) -> files.put(javaDir + path, source));
            for (NativeTestWriter.TestClass test : new NativeTestWriter(inventory, naming, options.supportPackage(), glue,
                    rewriter.parameterTypes()).write()) {
                files.put(javaDir + test.relativePath(), test.source());
                generatedIds.addAll(test.testCaseIds());
            }
            for (Hook hook : inventory.hooks()) {
                if (hook.phase().endsWith("All")) {
                    problems.add("@" + hook.phase() + " hook " + hook.id() + " is not called in native mode; "
                            + "move it to a JUnit @BeforeAll/@AfterAll method or a JUnit extension");
                }
            }
        }
        RuntimeSources.load(options.supportPackage(), runtimeClasses)
                .forEach((name, source) -> files.put(supportDir + name + ".java", source));

        List<String> missing = new ArrayList<>();
        for (TestCase tc : inventory.testCases()) {
            if (!generatedIds.contains(tc.id())) {
                missing.add(tc.id());
            }
        }
        int blocked = (int) inventory.assessments().stream().filter(a -> TestCaseAssessment.BLOCKED.equals(a.status())).count();
        List<TranslatedSource.Todo> todos = translation.todos();

        ReportData report = new ReportData(inventory, naming);
        files.put("traceability.csv", Traceability.csv(report));
        boolean keptExternal = inventory.callGraph().nodes().stream()
                .filter(n -> used.contains(n.id()))
                .flatMap(n -> n.apiUsages().stream())
                .anyMatch(u -> io.reggraeton.model.CallGraph.ApiUsage.EXTERNAL_KEPT.equals(u.category()));
        ProjectFiles.Carried carried = keptExternal ? ProjectFiles.carriedDependencies(inventory.layout()) : null;
        if (carried != null && !carried.withoutVersion().isEmpty()) {
            problems.add("Original dependencies with unknown version (not added to pom.xml): "
                    + String.join(", ", carried.withoutVersion()));
        }
        files.put("pom.xml", ProjectFiles.pom(options, inventory.layout(), lambdaGlue && options.mode() == ConvertOptions.Mode.BDD,
                carried));
        files.put("Jenkinsfile", ProjectFiles.jenkinsfile());
        files.put(".gitignore", ProjectFiles.gitignore());
        files.put("README.md", ProjectFiles.readme(options, generatedIds.size(), todos.size(), blocked));
        files.put("conversion-report.md", ConversionReport.render(options, inventory, report, translation, todos, problems,
                missing, generatedIds.size()));

        writeAll();
        return new Result(options.output().toAbsolutePath().normalize(), new ArrayList<>(files.keySet()),
                inventory.testCases().size(), generatedIds.size(), missing, todos, problems, blocked);
    }

    /** bdd mode: copies the feature files and writes the Cucumber suite runner; returns covered test case ids. */
    private Set<String> writeBdd(Inventory inventory, TestNaming naming) {
        Set<String> covered = new HashSet<>();
        Path root = Path.of(inventory.layout().root());
        for (FeatureFile feature : inventory.features()) {
            String target = naming.bddFeaturePath(feature.path());
            if (target == null) {
                continue; // unparseable: reported in the assessment, nothing to run
            }
            try {
                files.put(target, Files.readString(root.resolve(feature.path()), StandardCharsets.UTF_8));
            } catch (IOException e) {
                problems.add("could not copy " + feature.path() + ": " + e.getMessage());
                continue;
            }
            inventory.testCases().stream().filter(tc -> tc.feature().equals(feature.path())).forEach(tc -> covered.add(tc.id()));
        }

        Set<String> gluePackages = new LinkedHashSet<>();
        inventory.stepDefinitions().forEach(s -> gluePackages.add(packageOf(s.className())));
        inventory.hooks().forEach(h -> gluePackages.add(packageOf(h.className())));
        inventory.parameterTypes().forEach(p -> gluePackages.add(packageOf(p.className())));
        inventory.dataTableTypes().forEach(d -> gluePackages.add(packageOf(d.className())));
        gluePackages.add(options.supportPackage());
        gluePackages.remove("");

        String runner = """
                package %s;

                import org.junit.platform.suite.api.ConfigurationParameter;
                import org.junit.platform.suite.api.IncludeEngines;
                import org.junit.platform.suite.api.SelectClasspathResource;
                import org.junit.platform.suite.api.Suite;

                import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
                import static io.cucumber.junit.platform.engine.Constants.JUNIT_PLATFORM_NAMING_STRATEGY_PROPERTY_NAME;
                import static io.cucumber.junit.platform.engine.Constants.JUNIT_PLATFORM_SUREFIRE_NAMING_STRATEGY_EXAMPLE_NAME_PROPERTY_NAME;
                import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

                /**
                 * Runs every feature file under src/test/resources/features with Cucumber, against Playwright.
                 * Select scenarios with -Dcucumber.filter.tags="@smoke" or -Dcucumber.features=path/to.feature:line.
                 * Generated by reggraeton.
                 */
                @Suite
                @IncludeEngines("cucumber")
                @SelectClasspathResource("features")
                @ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "%s")
                @ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, html:target/cucumber-report.html")
                // JUnit XML names a test "<feature> > <scenario>" (outline rows numbered), so reports and
                // 'reggraeton compare' can tell scenarios apart
                @ConfigurationParameter(key = JUNIT_PLATFORM_NAMING_STRATEGY_PROPERTY_NAME, value = "surefire")
                @ConfigurationParameter(key = JUNIT_PLATFORM_SUREFIRE_NAMING_STRATEGY_EXAMPLE_NAME_PROPERTY_NAME,
                        value = "number-and-pickle-if-parameterized")
                public class RunRegressionTest {
                }
                """.formatted(options.basePackage(), String.join(",", gluePackages));
        files.put("src/test/java/" + options.basePackage().replace('.', '/') + "/RunRegressionTest.java", runner);
        return covered;
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private void writeAll() {
        Path out = options.output();
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = out.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Exposed for tests: generated files by relative path. */
    Map<String, String> files() {
        return new LinkedHashMap<>(files);
    }
}

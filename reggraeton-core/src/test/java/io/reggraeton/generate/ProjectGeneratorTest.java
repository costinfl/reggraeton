package io.reggraeton.generate;

import io.reggraeton.Samples;
import io.reggraeton.scan.RepositoryScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGeneratorTest {

    private static RepositoryScanner.Scan scan;

    @BeforeAll
    static void scanSample() {
        scan = new RepositoryScanner().scanWithSources(Samples.legacyShop());
    }

    private static ProjectGenerator.Result generate(Path out, ConvertOptions.Mode mode) {
        // the translator mutates the parsed sources, so every generation needs a fresh scan
        RepositoryScanner.Scan fresh = new RepositoryScanner().scanWithSources(Samples.legacyShop());
        return new ProjectGenerator(fresh, new ConvertOptions(out, mode, null, null, null)).generate();
    }

    @ParameterizedTest
    @EnumSource(ConvertOptions.Mode.class)
    void generatesACompleteCompilableProject(ConvertOptions.Mode mode, @TempDir Path dir) throws Exception {
        Path out = dir.resolve("project");
        ProjectGenerator.Result result = generate(out, mode);

        assertThat(result.complete()).isTrue();
        assertThat(result.generatedTests()).isEqualTo(scan.inventory().testCases().size()).isEqualTo(12);
        assertThat(result.blockedTests()).isEqualTo(1);
        assertThat(result.todos()).hasSize(3);
        assertThat(result.files()).contains("pom.xml", "Jenkinsfile", "README.md", "traceability.csv",
                "conversion-report.md", ".gitignore", "src/test/java/regression/support/PlaywrightSession.java");

        String pom = Files.readString(out.resolve("pom.xml"));
        assertThat(pom).contains("<PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD>1</PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD>")
                .contains("<artifactId>playwright</artifactId>").doesNotContain("selenium");
        String jenkinsfile = Files.readString(out.resolve("Jenkinsfile"));
        assertThat(jenkinsfile).contains("BASE_URL").contains("HEADLESS").contains("isUnix()")
                .contains("junit testResults: 'target/surefire-reports/*.xml'");

        Map<String, String> javaSources = new LinkedHashMap<>();
        for (String file : result.files()) {
            if (file.endsWith(".java")) {
                javaSources.put(file.substring("src/test/java/".length()), Files.readString(out.resolve(file)));
            }
        }
        assertThat(javaSources.values()).noneMatch(s -> s.contains("import org.openqa.selenium"));
        List<String> errors = InMemoryCompiler.compile(javaSources, dir.resolve("compile"));
        assertThat(errors).as("compiler errors").isEmpty();

        if (mode == ConvertOptions.Mode.BDD) {
            assertThat(result.files()).contains("src/test/resources/features/login.feature",
                    "src/test/resources/features/shop/cart.feature", "src/test/java/regression/RunRegressionTest.java",
                    "src/test/java/regression/support/PlaywrightHooks.java");
            assertThat(Files.readString(out.resolve("src/test/resources/features/login.feature")))
                    .isEqualTo(Files.readString(Samples.legacyShop().resolve("src/test/resources/features/login.feature")));
            assertThat(Files.readString(out.resolve("src/test/java/regression/RunRegressionTest.java")))
                    .contains("com.example.shop.steps,com.example.shop.hooks,regression.support");
        } else {
            assertThat(result.files()).contains("src/test/java/regression/features/LoginTest.java",
                    "src/test/java/regression/features/shop/ShoppingCartTest.java",
                    "src/test/java/regression/features/shop/ProductSearchTest.java");
            assertThat(javaSources.values()).noneMatch(s -> s.contains("import io.cucumber.java") || s.contains("import io.cucumber.datatable"));
            String cart = javaSources.get("regression/features/shop/ShoppingCartTest.java");
            assertThat(cart).contains("glue(ParameterTypes.class).amount(\"1329.97\")")
                    .contains("DataTable.of(new String[][] {{\"product\", \"quantity\"}")
                    .contains("before(() -> glue(Hooks.class).clearCart());");
            String search = javaSources.get("regression/features/shop/ProductSearchTest.java");
            assertThat(search).contains("undefined(\"Undefined step (search.feature:19)");
            assertThat(javaSources.get("regression/features/LoginTest.java"))
                    .doesNotContain("clearCart") // @cart hook does not apply to login scenarios
                    .contains("@ParameterizedTest");
        }
    }

    @Test
    void carriesOriginalDependenciesExceptReplacedOnes() {
        io.reggraeton.model.ProjectLayout layout = new io.reggraeton.model.ProjectLayout("/r", "maven", List.of(), List.of(),
                List.of(), List.of(), List.of(), "none", new java.util.TreeMap<>(java.util.Map.of(
                "org.seleniumhq.selenium:selenium-java", "4.21.0",
                "io.cucumber:cucumber-java", "7.18.0",
                "org.apache.commons:commons-lang3", "3.14.0",
                "com.acme:evidence", "?")));

        ProjectFiles.Carried carried = ProjectFiles.carriedDependencies(layout);

        assertThat(carried.withVersion()).containsOnlyKeys("org.apache.commons:commons-lang3");
        assertThat(carried.withoutVersion()).containsExactly("com.acme:evidence");
        String pom = ProjectFiles.pom(new ConvertOptions(Path.of("x"), ConvertOptions.Mode.NATIVE, null, null, null),
                layout, false, carried);
        assertThat(pom).contains("<artifactId>commons-lang3</artifactId>").contains("com.acme:evidence")
                .doesNotContain("selenium-java");
    }

    @Test
    void conversionReportListsManualWork(@TempDir Path dir) throws Exception {
        generate(dir, ConvertOptions.Mode.NATIVE);

        String report = Files.readString(dir.resolve("conversion-report.md"));
        assertThat(report).contains("| Generated tests | 12 |").contains("WebDriver.Options.logs")
                .contains("src/test/java/com/example/shop/steps/SearchSteps.java:48").contains("blocked");
    }
}

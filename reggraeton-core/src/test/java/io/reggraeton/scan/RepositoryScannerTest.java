package io.reggraeton.scan;

import io.reggraeton.Samples;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.Finding;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.StepMatch;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;
import io.reggraeton.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryScannerTest {

    @Test
    void everyScenarioAndExamplesRowBecomesOneTestCase() {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());

        assertThat(inventory.features()).hasSize(3);
        // login: 1 scenario + 3 outline rows, cart: 3, search: 3
        assertThat(inventory.testCases()).hasSize(10);
        assertThat(inventory.testCases()).extracting(TestCase::id).doesNotHaveDuplicates();
    }

    @Test
    void assessesReadinessAndReportsFindings() {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());

        assertThat(inventory.stepDefinitions()).hasSize(19);
        assertThat(inventory.hooks()).extracting(h -> h.phase() + h.order())
                .containsExactly("Before0", "Before10", "After10000");
        assertThat(inventory.assessments()).hasSize(inventory.testCases().size());

        Map<String, TestCaseAssessment> byId = inventory.assessments().stream()
                .collect(Collectors.toMap(TestCaseAssessment::testCaseId, a -> a));
        assertThat(byId.get("src/test/resources/features/login.feature:11").status()).isEqualTo(TestCaseAssessment.READY);
        assertThat(byId.get("src/test/resources/features/shop/search.feature:12").status()).isEqualTo(TestCaseAssessment.PARTIAL);
        assertThat(byId.get("src/test/resources/features/shop/search.feature:17").status()).isEqualTo(TestCaseAssessment.BLOCKED);
        assertThat(inventory.assessments()).filteredOn(a -> a.status().equals(TestCaseAssessment.READY)).hasSize(8);

        // the @cart hook applies to cart scenarios only
        assertThat(byId.get("src/test/resources/features/shop/cart.feature:10").hookIds())
                .contains("com.example.shop.hooks.Hooks#clearCart()");
        assertThat(byId.get("src/test/resources/features/login.feature:11").hookIds())
                .doesNotContain("com.example.shop.hooks.Hooks#clearCart()");

        assertThat(inventory.findings()).extracting(Finding::code)
                .contains("UNDEFINED_STEP", "UNUSED_STEP_DEFINITION", "MANUAL_CONVERSION")
                .doesNotContain("AMBIGUOUS_STEP", "JAVA_PARSE_ERROR", "OUTSIDE_GLUE");

        TestStep amount = inventory.testCases().stream()
                .flatMap(tc -> tc.steps().stream())
                .filter(s -> s.text().equals("the cart total is 1329.97 EUR"))
                .findFirst().orElseThrow();
        assertThat(amount.match().status()).isEqualTo(StepMatch.MATCHED);
        assertThat(amount.match().arguments()).singleElement()
                .satisfies(a -> assertThat(a.parameterType()).isEqualTo("amount"));
    }

    @Test
    void ignoresGlueOutsideConfiguredPackages(@TempDir Path dir) {
        new io.reggraeton.TempRepo(dir)
                .file("src/test/resources/junit-platform.properties", "cucumber.glue=a.steps\n")
                .file("src/test/resources/f.feature", "Feature: f\n  Scenario: s\n    Given a step\n")
                .java("a.steps", "Inside", "import io.cucumber.java.en.Given;\npublic class Inside { @Given(\"a step\") public void x() {} }\n")
                .java("b.steps", "Outside", "import io.cucumber.java.en.Given;\npublic class Outside { @Given(\"a step\") public void y() {} }\n");

        Inventory inventory = new RepositoryScanner().scan(dir);

        assertThat(inventory.stepDefinitions()).extracting(StepDefinition::className).containsExactly("a.steps.Inside");
        assertThat(inventory.testCases().get(0).steps().get(0).match().status()).isEqualTo(StepMatch.MATCHED);
        assertThat(inventory.findings()).extracting(Finding::code).contains("OUTSIDE_GLUE");
    }

    @Test
    void inventoryRoundTripsThroughJson(@TempDir Path dir) {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());
        Path file = dir.resolve("inventory.json");

        Json.write(file, inventory);
        Inventory back = Json.read(file, Inventory.class);

        assertThat(back).isEqualTo(inventory);
    }
}

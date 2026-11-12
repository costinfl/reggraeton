package io.reggraeton.scan;

import io.reggraeton.Samples;
import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.ScenarioDefinition;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureParserTest {

    private final FeatureParser parser = new FeatureParser();

    private FeatureParser.Parsed parseSample(String relative) {
        Path root = Samples.legacyShop();
        return parser.parse(root.resolve(relative), relative);
    }

    @Test
    void expandsOutlineRowsAndInlinesBackground() {
        FeatureParser.Parsed parsed = parseSample("src/test/resources/features/login.feature");

        FeatureFile feature = parsed.feature();
        assertThat(feature.parseError()).isNull();
        assertThat(feature.name()).isEqualTo("Login");
        assertThat(feature.tags()).containsExactly("@auth");
        assertThat(feature.background()).hasSize(1);
        assertThat(feature.scenarios()).hasSize(2);

        ScenarioDefinition outline = feature.scenarios().get(1);
        assertThat(outline.outline()).isTrue();
        assertThat(outline.examples()).hasSize(2);
        assertThat(outline.examples().get(0).header()).containsExactly("user", "password", "error");

        List<TestCase> cases = parsed.testCases();
        assertThat(cases).hasSize(4);

        TestCase plain = cases.get(0);
        assertThat(plain.id()).isEqualTo("src/test/resources/features/login.feature:11");
        assertThat(plain.exampleLine()).isNull();
        assertThat(plain.tags()).containsExactly("@auth", "@smoke");
        assertThat(plain.steps()).extracting(TestStep::text).containsExactly(
                "I am on the login page",
                "I log in as \"alice\" with password \"secret123\"",
                "I should see the welcome message \"Welcome, alice\"");
        assertThat(plain.steps().get(0).fromBackground()).isTrue();
        assertThat(plain.steps().get(1).fromBackground()).isFalse();

        TestCase locked = cases.get(3);
        assertThat(locked.scenarioName()).isEqualTo("Rejected login");
        assertThat(locked.exampleIndex()).isEqualTo(3);
        assertThat(locked.examplesName()).isEqualTo("locked account");
        assertThat(locked.id()).isEqualTo("src/test/resources/features/login.feature:" + locked.exampleLine());
        assertThat(locked.tags()).containsExactly("@auth", "@regression");
        assertThat(locked.steps().get(1).text()).isEqualTo("I log in as \"bob\" with password \"secret123\"");
        assertThat(locked.steps().get(2).text()).isEqualTo("I should see the error \"Account locked\"");
    }

    @Test
    void keepsRulesDataTablesDocStringsAndResolvesAndKeyword() {
        FeatureParser.Parsed parsed = parseSample("src/test/resources/features/shop/cart.feature");

        assertThat(parsed.feature().scenarios()).extracting(ScenarioDefinition::rule)
                .containsExactly("Adding products", "Adding products", "Checkout");
        assertThat(parsed.testCases()).hasSize(3);

        TestCase single = parsed.testCases().get(0);
        TestStep and = single.steps().get(2);
        assertThat(and.keyword()).isEqualTo("And");
        assertThat(and.type()).isEqualTo("Action");
        assertThat(and.effectiveKeyword()).isEqualTo("When");

        TestStep table = parsed.testCases().get(1).steps().get(1);
        assertThat(table.argument().isDataTable()).isTrue();
        assertThat(table.argument().rows()).hasSize(3);
        assertThat(table.argument().rows().get(2)).containsExactly("USB-C Cable", "3");

        TestStep doc = parsed.testCases().get(2).steps().get(2);
        assertThat(doc.argument().isDocString()).isTrue();
        assertThat(doc.argument().content()).isEqualTo("Please leave the parcel at the door.");
    }

    @Test
    void reportsParseErrorsInsteadOfFailing(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.feature");
        Files.writeString(broken, "Feature: Broken\n  Scenario: x\n    Given ok\n  | not | a table here\n  Oops\n");

        FeatureParser.Parsed parsed = parser.parse(broken, "broken.feature");

        assertThat(parsed.feature().parseError()).isNotBlank();
        assertThat(parsed.testCases()).isEmpty();
    }

    @Test
    void reportsEmptyFiles(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.feature");
        Files.writeString(empty, "# nothing here yet\n");

        FeatureParser.Parsed parsed = parser.parse(empty, "empty.feature");

        assertThat(parsed.feature().parseError()).contains("Empty");
        assertThat(parsed.testCases()).isEmpty();
    }
}

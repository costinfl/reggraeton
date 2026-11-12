package io.reggraeton.scan.java;

import io.reggraeton.Samples;
import io.reggraeton.TempRepo;
import io.reggraeton.model.Hook;
import io.reggraeton.model.StepDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlueScannerTest {

    private static GlueScanner.Glue scan(Path root) {
        return new GlueScanner(JavaSourceIndex.build(root, List.of())).scan();
    }

    @Test
    void findsAnnotationLambdaAndRegexStepsInLegacyShop() {
        GlueScanner.Glue glue = scan(Samples.legacyShop());

        assertThat(glue.stepDefinitions()).hasSize(19);
        assertThat(glue.stepDefinitions()).filteredOn(s -> s.style().equals(StepDefinition.LAMBDA)).hasSize(8);
        assertThat(glue.stepDefinitions()).filteredOn(s -> s.expressionType().equals(StepDefinition.REGULAR_EXPRESSION))
                .extracting(StepDefinition::expression)
                .containsExactlyInAnyOrder("^I search for \"([^\"]*)\"$", "^I sort the results by \"(.*)\"$",
                        "^the first result is \"(.*)\"$");

        StepDefinition login = glue.stepDefinitions().stream()
                .filter(s -> s.expression().equals("I log in as {string} with password {string}")).findFirst().orElseThrow();
        assertThat(login.id()).isEqualTo("com.example.shop.steps.LoginSteps#iLogInAs(String,String)");
        assertThat(login.keyword()).isEqualTo("When");
        assertThat(login.parameters()).extracting(p -> p.type()).containsExactly("String", "String");

        StepDefinition lambda = glue.stepDefinitions().stream()
                .filter(s -> s.expression().equals("the cart total is {amount} EUR")).findFirst().orElseThrow();
        assertThat(lambda.id()).startsWith("com.example.shop.steps.CartSteps#Then@L");
        assertThat(lambda.parameters()).extracting(p -> p.type()).containsExactly("BigDecimal");

        assertThat(glue.hooks()).extracting(Hook::phase, Hook::tagExpression, Hook::order).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("Before", "", 0),
                org.assertj.core.groups.Tuple.tuple("Before", "@cart", 10),
                org.assertj.core.groups.Tuple.tuple("After", "", Hook.DEFAULT_ORDER));
        assertThat(glue.parameterTypes()).singleElement()
                .satisfies(p -> {
                    assertThat(p.name()).isEqualTo("amount");
                    assertThat(p.regexps()).containsExactly("\\d+\\.\\d{2}");
                });
        assertThat(glue.findings()).isEmpty();
    }

    @Test
    void evaluatesConstantsRepeatedAnnotationsAndOtherLanguages(@TempDir Path dir) {
        new TempRepo(dir)
                .java("x.steps", "Patterns", """
                        public final class Patterns {
                            public static final String PREFIX = "the user ";
                        }
                        """)
                .java("x.steps", "Steps", """
                        import io.cucumber.java.en.*;
                        import io.cucumber.java.de.Angenommen;
                        import io.cucumber.java.ParameterType;
                        import io.cucumber.java.Before;

                        public class Steps {
                            @Given(Patterns.PREFIX + "is {word}")
                            public void a(String w) {}

                            @When("first")
                            @When("second")
                            public void b() {}

                            @Angenommen("ich bin angemeldet")
                            public void c() {}

                            @Then(value = "dynamic " + System.currentTimeMillis())
                            public void d() {}

                            @ParameterType(name = "color", value = "red|green")
                            public String colour(String s) { return s; }

                            @Before(value = "@ui and not @api", order = 5)
                            public void before() {}
                        }
                        """);

        GlueScanner.Glue glue = scan(dir);

        assertThat(glue.stepDefinitions()).extracting(StepDefinition::expression)
                .containsExactly("the user is {word}", "first", "second", "ich bin angemeldet");
        assertThat(glue.stepDefinitions()).extracting(StepDefinition::id).contains(
                "x.steps.Steps#b()[1]", "x.steps.Steps#b()[2]");
        assertThat(glue.stepDefinitions().get(3).keyword()).isEqualTo("Angenommen");
        assertThat(glue.findings()).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("NON_CONSTANT_STEP_PATTERN"));
        assertThat(glue.parameterTypes()).singleElement()
                .satisfies(p -> assertThat(p.name()).isEqualTo("color"));
        assertThat(glue.hooks()).singleElement()
                .satisfies(h -> {
                    assertThat(h.tagExpression()).isEqualTo("@ui and not @api");
                    assertThat(h.order()).isEqualTo(5);
                });
    }

    @Test
    void expressionTypeFollowsCucumberRules() {
        assertThat(GlueScanner.expressionType("^starts")).isEqualTo(StepDefinition.REGULAR_EXPRESSION);
        assertThat(GlueScanner.expressionType("ends$")).isEqualTo(StepDefinition.REGULAR_EXPRESSION);
        assertThat(GlueScanner.expressionType("/slashed/")).isEqualTo(StepDefinition.REGULAR_EXPRESSION);
        assertThat(GlueScanner.expressionType("I have {int} cukes")).isEqualTo(StepDefinition.CUCUMBER_EXPRESSION);
    }
}

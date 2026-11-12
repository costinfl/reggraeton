package io.reggraeton.scan;

import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.StepMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepMatcherTest {

    private static StepDefinition def(String id, String expression) {
        return new StepDefinition(id, "Given", expression,
                io.reggraeton.scan.java.GlueScanner.expressionType(expression),
                StepDefinition.ANNOTATION, "x.Steps", id, List.of(), "Steps.java", 1);
    }

    @Test
    void matchesCucumberExpressionsAndExtractsArguments() {
        StepMatcher matcher = new StepMatcher(List.of(def("a", "I have {int} cucumber(s) in my {word}")), List.of());

        StepMatch match = matcher.match("I have 42 cucumbers in my belly");

        assertThat(match.status()).isEqualTo(StepMatch.MATCHED);
        assertThat(match.stepDefinitionIds()).containsExactly("a");
        assertThat(match.arguments()).extracting(StepMatch.MatchedArgument::value).containsExactly("42", "belly");
        assertThat(match.arguments()).extracting(StepMatch.MatchedArgument::parameterType).containsExactly("int", "word");
        assertThat(match.arguments().get(0).start()).isEqualTo(7);
    }

    @Test
    void matchesRegularExpressions() {
        StepMatcher matcher = new StepMatcher(List.of(def("r", "^I search for \"([^\"]*)\"$")), List.of());

        StepMatch match = matcher.match("I search for \"cable\"");

        assertThat(match.status()).isEqualTo(StepMatch.MATCHED);
        assertThat(match.arguments()).singleElement().satisfies(a -> assertThat(a.value()).isEqualTo("cable"));
    }

    @Test
    void reportsUndefinedAndAmbiguous() {
        StepMatcher matcher = new StepMatcher(List.of(
                def("one", "the badge shows {int} item(s)"),
                def("two", "^the badge shows (\\d+) items?$")), List.of());

        assertThat(matcher.match("nothing matches this").status()).isEqualTo(StepMatch.UNDEFINED);
        StepMatch ambiguous = matcher.match("the badge shows 3 items");
        assertThat(ambiguous.status()).isEqualTo(StepMatch.AMBIGUOUS);
        assertThat(ambiguous.stepDefinitionIds()).containsExactly("one", "two");
    }

    @Test
    void usesCustomParameterTypes() {
        ParameterTypeDefinition amount = new ParameterTypeDefinition("amount", List.of("\\d+\\.\\d{2}"),
                "x.Types", "amount", "BigDecimal", "Types.java", 3);
        StepMatcher matcher = new StepMatcher(List.of(def("t", "the total is {amount} EUR")), List.of(amount));

        StepMatch match = matcher.match("the total is 12.50 EUR");

        assertThat(match.status()).isEqualTo(StepMatch.MATCHED);
        assertThat(match.arguments()).singleElement().satisfies(a -> {
            assertThat(a.value()).isEqualTo("12.50");
            assertThat(a.parameterType()).isEqualTo("amount");
        });
        assertThat(matcher.match("the total is 12.5 EUR").status()).isEqualTo(StepMatch.UNDEFINED);
    }

    @Test
    void reportsPatternsUsingUnknownParameterTypes() {
        StepMatcher matcher = new StepMatcher(List.of(def("u", "I pay {money}")), List.of());

        assertThat(matcher.findings()).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("INVALID_STEP_PATTERN"));
        assertThat(matcher.match("I pay 5").status()).isEqualTo(StepMatch.UNDEFINED);
    }
}

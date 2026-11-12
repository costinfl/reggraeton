package io.reggraeton.runtime;

import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;

import java.util.List;

/**
 * native mode: Cucumber tag expressions for selecting tests, read from {@code -Dcucumber.filter.tags}
 * (or {@code -Dtags}, or {@code CUCUMBER_FILTER_TAGS}), so both modes are filtered the same way.
 */
public final class TagFilter {

    private TagFilter() {
    }

    public static String expression() {
        String value = RunConfig.get("cucumber.filter.tags", "CUCUMBER_FILTER_TAGS", "");
        if (value.isBlank()) {
            value = RunConfig.get("tags", "TAGS", "");
        }
        return value.trim();
    }

    public static boolean accepts(List<String> tags) {
        String expression = expression();
        if (expression.isEmpty()) {
            return true;
        }
        Expression parsed = TagExpressionParser.parse(expression);
        return parsed.evaluate(tags);
    }
}

package io.reggraeton.scan;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.CaptureGroupTransformer;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.reggraeton.model.Finding;
import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.StepMatch;
import io.reggraeton.model.StepMatch.MatchedArgument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Matches step text against step definitions with Cucumber's own expression engine, including
 * custom parameter types, so the result is what Cucumber-JVM would do at run time.
 */
public final class StepMatcher {

    private final Map<StepDefinition, Expression> expressions = new LinkedHashMap<>();
    private final List<Finding> findings = new ArrayList<>();

    public StepMatcher(List<StepDefinition> definitions, List<ParameterTypeDefinition> parameterTypes) {
        ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.ENGLISH);
        for (ParameterTypeDefinition type : parameterTypes) {
            if (type.regexps().isEmpty()) {
                findings.add(Finding.warning("INVALID_PARAMETER_TYPE",
                        "Parameter type {" + type.name() + "} has no regular expression", type.file(), type.line()));
                continue;
            }
            try {
                CaptureGroupTransformer<String> raw = groups -> groups.length == 0 ? null : groups[0];
                registry.defineParameterType(new ParameterType<>(type.name(), type.regexps(), String.class, raw, false, false));
            } catch (RuntimeException e) {
                findings.add(Finding.warning("INVALID_PARAMETER_TYPE",
                        "Parameter type {" + type.name() + "} could not be registered: " + e.getMessage(), type.file(), type.line()));
            }
        }
        ExpressionFactory factory = new ExpressionFactory(registry);
        for (StepDefinition definition : definitions) {
            try {
                expressions.put(definition, factory.createExpression(definition.expression()));
            } catch (RuntimeException e) {
                findings.add(Finding.error("INVALID_STEP_PATTERN",
                        "Step pattern \"" + definition.expression() + "\" is invalid: " + firstLine(e.getMessage()),
                        definition.file(), definition.line()));
            }
        }
    }

    /** Problems found while compiling the step patterns. */
    public List<Finding> findings() {
        return findings;
    }

    public StepMatch match(String stepText) {
        List<String> ids = new ArrayList<>();
        List<MatchedArgument> arguments = List.of();
        for (Map.Entry<StepDefinition, Expression> entry : expressions.entrySet()) {
            Optional<List<Argument<?>>> result;
            try {
                result = entry.getValue().match(stepText);
            } catch (RuntimeException e) {
                continue; // e.g. transformer failure on overflowing {int}: Cucumber would not match either
            }
            if (result.isPresent()) {
                ids.add(entry.getKey().id());
                if (ids.size() == 1) {
                    arguments = toArguments(result.get());
                }
            }
        }
        if (ids.isEmpty()) {
            return new StepMatch(StepMatch.UNDEFINED, List.of(), List.of());
        }
        if (ids.size() > 1) {
            return new StepMatch(StepMatch.AMBIGUOUS, ids, List.of());
        }
        return new StepMatch(StepMatch.MATCHED, ids, arguments);
    }

    private static List<MatchedArgument> toArguments(List<Argument<?>> arguments) {
        List<MatchedArgument> result = new ArrayList<>();
        for (Argument<?> argument : arguments) {
            String group = argument.getGroup() == null ? null : argument.getGroup().getValue();
            int start = argument.getGroup() == null || group == null ? -1 : argument.getGroup().getStart();
            Object value;
            try {
                value = argument.getValue();
            } catch (RuntimeException e) {
                value = group;
            }
            result.add(new MatchedArgument(group, value == null ? null : value.toString(),
                    argument.getParameterType().getName() == null ? "" : argument.getParameterType().getName(), start));
        }
        return result;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}

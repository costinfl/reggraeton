package io.reggraeton.generate;

import io.reggraeton.model.ParameterTypeDefinition;
import io.reggraeton.model.StepArgument;
import io.reggraeton.model.StepMatch.MatchedArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the arguments of a matched step as Java expressions for a direct call to the step
 * method, applying the conversions Cucumber would apply at run time.
 */
final class ArgumentRenderer {

    /** Rendered arguments, or the reason they cannot be rendered. */
    record Rendered(List<String> expressions, String problem) {
        static Rendered ok(List<String> expressions) {
            return new Rendered(expressions, null);
        }

        static Rendered fail(String problem) {
            return new Rendered(List.of(), problem);
        }

        boolean failed() {
            return problem != null;
        }
    }

    private final Map<String, ParameterTypeDefinition> parameterTypes;
    private final Set<String> enums;

    /**
     * @param parameterTypes custom parameter types by name
     * @param enums          simple names of enums declared in the converted code
     */
    ArgumentRenderer(Map<String, ParameterTypeDefinition> parameterTypes, Set<String> enums) {
        this.parameterTypes = parameterTypes;
        this.enums = enums;
    }

    /**
     * @param parameterTypesOfMethod declared Java types of the step method's parameters
     * @param glueCall               renders a call on a glue object: class name, method, argument
     */
    Rendered render(List<MatchedArgument> arguments, StepArgument stepArgument, List<String> parameterTypesOfMethod,
                    GlueCall glueCall) {
        int expected = arguments.size() + (stepArgument == null ? 0 : 1);
        if (parameterTypesOfMethod.size() != expected) {
            return Rendered.fail("step method takes " + parameterTypesOfMethod.size() + " parameter(s) but the step provides "
                    + expected + "; Cucumber-specific parameters are not supported in native mode");
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String javaType = normalise(parameterTypesOfMethod.get(i));
            MatchedArgument argument = arguments.get(i);
            ParameterTypeDefinition custom = parameterTypes.get(argument.parameterType());
            String expression;
            if (custom != null) {
                if (custom.methodName() == null) {
                    return Rendered.fail("parameter type {" + custom.name() + "} is defined as a lambda; convert it by hand");
                }
                String call = glueCall.render(custom.className(), custom.methodName(),
                        argument.group() == null ? "null" : JavaLiterals.string(argument.group()));
                boolean knownReturn = custom.returnType() != null && !custom.returnType().equals("?")
                        && normalise(custom.returnType()).equals(javaType);
                expression = knownReturn ? call : "(" + parameterTypesOfMethod.get(i) + ") " + call;
            } else {
                String value = argument.value() != null ? argument.value() : argument.group();
                expression = scalar(javaType, value);
                if (expression == null) {
                    return Rendered.fail("cannot convert \"" + value + "\" to " + parameterTypesOfMethod.get(i));
                }
            }
            result.add(expression);
        }
        if (stepArgument != null) {
            String javaType = normalise(parameterTypesOfMethod.get(parameterTypesOfMethod.size() - 1));
            String expression = stepArgument.isDataTable() ? table(javaType, stepArgument.rows()) : docString(javaType, stepArgument.content());
            if (expression == null) {
                return Rendered.fail("cannot pass the " + (stepArgument.isDataTable() ? "data table" : "doc string")
                        + " as " + parameterTypesOfMethod.get(parameterTypesOfMethod.size() - 1));
            }
            result.add(expression);
        }
        return Rendered.ok(result);
    }

    /** Renders a call to a glue method, e.g. {@code glue(ParameterTypes.class).amount("1.00")}. */
    @FunctionalInterface
    interface GlueCall {
        String render(String className, String method, String argument);
    }

    static String normalise(String type) {
        String t = type.replace(" ", "").replace("final", "");
        if (t.startsWith("java.lang.")) {
            t = t.substring("java.lang.".length());
        }
        if (t.startsWith("java.math.")) {
            t = t.substring("java.math.".length());
        }
        return t.replace("java.util.", "");
    }

    private String scalar(String type, String value) {
        if (value == null) {
            return Set.of("int", "long", "double", "float", "short", "byte", "boolean", "char").contains(type) ? null : "null";
        }
        try {
            switch (type) {
                case "String", "Object", "CharSequence", "?":
                    return JavaLiterals.string(value);
                case "int", "Integer":
                    return String.valueOf(Integer.parseInt(value.trim()));
                case "long", "Long":
                    return Long.parseLong(value.trim()) + "L";
                case "short", "Short":
                    return "(short) " + Short.parseShort(value.trim());
                case "byte", "Byte":
                    return "(byte) " + Byte.parseByte(value.trim());
                case "double", "Double":
                    Double.parseDouble(value.trim());
                    return value.trim() + "d";
                case "float", "Float":
                    Float.parseFloat(value.trim());
                    return value.trim() + "f";
                case "boolean", "Boolean":
                    return String.valueOf(Boolean.parseBoolean(value.trim()));
                case "char", "Character":
                    return value.length() == 1 ? "'" + (value.equals("'") ? "\\'" : value.equals("\\") ? "\\\\" : value) + "'" : null;
                case "BigDecimal":
                    new java.math.BigDecimal(value.trim());
                    return "new java.math.BigDecimal(" + JavaLiterals.string(value.trim()) + ")";
                case "BigInteger":
                    new java.math.BigInteger(value.trim());
                    return "new java.math.BigInteger(" + JavaLiterals.string(value.trim()) + ")";
                default:
                    if (enums.contains(type) && value.matches("[A-Za-z_$][\\w$]*")) {
                        return type + ".valueOf(" + JavaLiterals.string(value) + ")";
                    }
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String table(String type, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("DataTable.of(new String[][] {");
        for (int r = 0; r < rows.size(); r++) {
            sb.append(r == 0 ? "" : ", ").append('{');
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                sb.append(c == 0 ? "" : ", ").append(JavaLiterals.string(row.get(c)));
            }
            sb.append('}');
        }
        String table = sb.append("})").toString();
        return switch (type) {
            case "DataTable" -> table;
            case "List<Map<String,String>>" -> table + ".asMaps()";
            case "List<List<String>>" -> table + ".asLists()";
            case "List<String>" -> table + ".asList()";
            case "Map<String,String>" -> table + ".asMap()";
            default -> null;
        };
    }

    private static String docString(String type, String content) {
        return switch (type) {
            case "String", "Object", "CharSequence", "?" -> JavaLiterals.string(content);
            default -> null;
        };
    }
}

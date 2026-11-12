package io.reggraeton.translate;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.reggraeton.scan.java.Expressions;

/**
 * Turns Selenium locator strategies ({@code By.id}, {@code @FindBy(css = ...)}, ...) into
 * Playwright selector expressions.
 */
final class Selectors {

    private Selectors() {
    }

    /**
     * @param strategy {@code By} factory name ({@code id}, {@code cssSelector}, ...) or {@code How}/{@code @FindBy}
     *                 member name ({@code ID}, {@code css}, ...)
     * @return the selector expression, or null for an unknown strategy
     */
    static Expression selector(String strategy, Expression value) {
        String constant = Expressions.constantString(value);
        switch (normalise(strategy)) {
            case "id":
                if (constant != null) {
                    return literal(constant.matches("[A-Za-z_][\\w-]*") ? "#" + constant : "id=" + constant);
                }
                return concat("id=", value, "");
            case "css":
                return constant != null ? literal(constant) : value.clone();
            case "xpath":
                return constant != null ? literal("xpath=" + constant) : concat("xpath=", value, "");
            case "name":
                return constant != null ? literal("[name=\"" + cssString(constant) + "\"]") : concat("[name=\"", value, "\"]");
            case "class":
                return constant != null ? literal("." + constant.trim()) : concat(".", value, "");
            case "tag":
                return constant != null ? literal(constant) : value.clone();
            case "linkText":
                return constant != null ? literal("a:text-is(\"" + cssString(constant) + "\")") : concat("a:text-is(\"", value, "\")");
            case "partialLinkText":
                return constant != null ? literal("a:has-text(\"" + cssString(constant) + "\")") : concat("a:has-text(\"", value, "\")");
            case "idOrName":
                return constant != null
                        ? literal("[id=\"" + cssString(constant) + "\"], [name=\"" + cssString(constant) + "\"]")
                        : null;
            default:
                return null;
        }
    }

    private static String normalise(String strategy) {
        return switch (strategy) {
            case "id", "ID" -> "id";
            case "cssSelector", "css", "CSS" -> "css";
            case "xpath", "XPATH" -> "xpath";
            case "name", "NAME" -> "name";
            case "className", "CLASS_NAME" -> "class";
            case "tagName", "TAG_NAME" -> "tag";
            case "linkText", "LINK_TEXT" -> "linkText";
            case "partialLinkText", "PARTIAL_LINK_TEXT" -> "partialLinkText";
            case "ID_OR_NAME" -> "idOrName";
            default -> strategy;
        };
    }

    /** True for selectors that can match at most one element ({@code #id}). */
    static boolean isUnique(Expression selector) {
        if (selector instanceof StringLiteralExpr s) {
            String v = s.asString();
            return v.matches("#[A-Za-z_][\\w-]*") || v.startsWith("id=");
        }
        return false;
    }

    static StringLiteralExpr literal(String value) {
        StringLiteralExpr literal = new StringLiteralExpr();
        literal.setString(value);
        return literal;
    }

    /** {@code prefix + value + suffix}, merging into adjacent literals where possible. */
    static Expression concat(String prefix, Expression value, String suffix) {
        Expression result = value.clone();
        if (!prefix.isEmpty()) {
            StringLiteralExpr leftmost = leftmostLiteral(result);
            if (leftmost != null) {
                leftmost.setString(prefix + leftmost.asString());
            } else {
                result = new BinaryExpr(literal(prefix), result, BinaryExpr.Operator.PLUS);
            }
        }
        if (!suffix.isEmpty()) {
            result = new BinaryExpr(result, literal(suffix), BinaryExpr.Operator.PLUS);
        }
        return result;
    }

    /** The first operand of a {@code "a" + b + ...} chain when it is a string literal. */
    private static StringLiteralExpr leftmostLiteral(Expression e) {
        Expression current = e;
        while (current instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            current = b.getLeft();
        }
        return current instanceof StringLiteralExpr s && current != e ? s : null;
    }

    private static String cssString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

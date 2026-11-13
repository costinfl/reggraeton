package io.reggraeton.scan.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

/** Text of string literals and of concatenations containing them ({@code "SELECT " + col + " FROM dual"}). */
final class SqlLiterals {

    private SqlLiterals() {
    }

    /** True for a literal or concatenation that is not itself part of a larger concatenation (and not in an annotation). */
    static boolean isTop(Expression e) {
        if (!(e instanceof StringLiteralExpr || e instanceof TextBlockLiteralExpr || isConcatenation(e))) {
            return false;
        }
        if (isConcatenation(e) && !containsLiteral(e)) {
            return false;
        }
        Node parent = e.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (parent instanceof Expression p && isConcatenation(p)) {
            return false;
        }
        return e.findAncestor(AnnotationExpr.class).isEmpty();
    }

    /** Literal parts joined; other operands become {@code ?}. */
    static String text(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return t.asString();
        }
        if (e instanceof EnclosedExpr en) {
            return text(en.getInner());
        }
        if (isConcatenation(e)) {
            BinaryExpr b = e.asBinaryExpr();
            return text(b.getLeft()) + text(b.getRight());
        }
        return "?";
    }

    private static boolean isConcatenation(Expression e) {
        return e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS;
    }

    private static boolean containsLiteral(Expression e) {
        return !e.findAll(StringLiteralExpr.class).isEmpty() || !e.findAll(TextBlockLiteralExpr.class).isEmpty();
    }
}

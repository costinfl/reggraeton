package io.reggraeton.scan.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Helpers to read annotation members and constant expressions from the AST. */
public final class Expressions {

    private Expressions() {
    }

    /** Value of annotation member {@code name} ({@code value} also covers the single-member form). */
    public static Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return "value".equals(name) ? Optional.of(single.getMemberValue()) : Optional.empty();
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(name)) {
                    return Optional.of(pair.getValue());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Evaluates a compile-time constant String: literals, text blocks, concatenation and references to
     * {@code static final} String constants declared in the scanned sources. Returns null if not constant.
     */
    public static String constantString(Expression expression) {
        return constantString(expression, 0);
    }

    private static String constantString(Expression e, int depth) {
        if (e == null || depth > 20) {
            return null;
        }
        if (e instanceof StringLiteralExpr s) {
            return unescape(s);
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return t.asString();
        }
        if (e instanceof EnclosedExpr enclosed) {
            return constantString(enclosed.getInner(), depth + 1);
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = constantString(b.getLeft(), depth + 1);
            String right = constantString(b.getRight(), depth + 1);
            if (left == null && b.getLeft() instanceof IntegerLiteralExpr i) {
                left = i.getValue();
            }
            if (right == null && b.getRight() instanceof IntegerLiteralExpr i) {
                right = i.getValue();
            }
            return left == null || right == null ? null : left + right;
        }
        if (e instanceof NameExpr || e instanceof FieldAccessExpr) {
            try {
                ResolvedValueDeclaration declaration = e instanceof NameExpr n ? n.resolve() : ((FieldAccessExpr) e).resolve();
                return declaration.toAst()
                        .filter(VariableDeclarator.class::isInstance)
                        .map(VariableDeclarator.class::cast)
                        .flatMap(VariableDeclarator::getInitializer)
                        .map(init -> constantString(init, depth + 1))
                        .orElseGet(() -> declaration.toAst()
                                .filter(FieldDeclaration.class::isInstance)
                                .map(FieldDeclaration.class::cast)
                                .flatMap(f -> f.getVariables().stream()
                                        .filter(v -> v.getNameAsString().equals(nameOf(e)))
                                        .findFirst())
                                .flatMap(VariableDeclarator::getInitializer)
                                .map(init -> constantString(init, depth + 1))
                                .orElse(null));
            } catch (RuntimeException | StackOverflowError ex) {
                return null;
            }
        }
        return null;
    }

    private static String nameOf(Expression e) {
        return e instanceof NameExpr n ? n.getNameAsString() : ((FieldAccessExpr) e).getNameAsString();
    }

    private static String unescape(StringLiteralExpr s) {
        try {
            return com.github.javaparser.utils.StringEscapeUtils.unescapeJava(s.getValue());
        } catch (RuntimeException e) {
            return s.getValue();
        }
    }

    /** All constant strings of a single value or an array initializer ({@code {"a", "b"}}). */
    public static List<String> constantStrings(Expression expression) {
        List<String> values = new ArrayList<>();
        if (expression instanceof ArrayInitializerExpr array) {
            for (Expression value : array.getValues()) {
                String s = constantString(value);
                if (s != null) {
                    values.add(s);
                }
            }
        } else {
            String s = constantString(expression);
            if (s != null) {
                values.add(s);
            }
        }
        return values;
    }

    public static Integer constantInt(Expression e) {
        if (e instanceof IntegerLiteralExpr i) {
            try {
                return i.asNumber().intValue();
            } catch (RuntimeException ex) {
                return null;
            }
        }
        if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
            Integer inner = constantInt(u.getExpression());
            return inner == null ? null : -inner;
        }
        if (e instanceof EnclosedExpr enclosed) {
            return constantInt(enclosed.getInner());
        }
        return null;
    }

    /**
     * Best-effort fully qualified name of an annotation, using the compilation unit's imports.
     * Returns the name as written when it cannot be qualified.
     */
    public static String qualifiedAnnotationName(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (name.contains(".") && Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        Optional<CompilationUnit> unit = annotation.findCompilationUnit();
        if (unit.isEmpty()) {
            return name;
        }
        String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String rest = name.contains(".") ? name.substring(name.indexOf('.')) : "";
        for (ImportDeclaration imp : unit.get().getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk() && imp.getName().getIdentifier().equals(first)) {
                return imp.getNameAsString() + rest;
            }
        }
        return qualifyFromWildcard(unit.get(), first) + rest;
    }

    /** Uses wildcard imports from well-known glue packages to qualify a simple name. */
    private static String qualifyFromWildcard(CompilationUnit unit, String simpleName) {
        for (ImportDeclaration imp : unit.getImports()) {
            if (!imp.isStatic() && imp.isAsterisk()) {
                String pkg = imp.getNameAsString();
                if (GlueAnnotations.isGluePackage(pkg) && GlueAnnotations.isKnownSimpleName(pkg, simpleName)) {
                    return pkg + "." + simpleName;
                }
            }
        }
        return simpleName;
    }
}

package io.reggraeton.scan.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Whether Selenium objects flow into or out of a call (used to decide if an external call can be kept). */
public final class SeleniumTypes {

    private SeleniumTypes() {
    }

    /**
     * True when the call's receiver or an argument is a Selenium object, or its result is used as one
     * (assigned to a Selenium-typed variable or used as the receiver of a Selenium call).
     */
    public static boolean involved(Expression call) {
        Optional<JavaTypes> types = call.findCompilationUnit().map(JavaTypes::new);
        if (types.isEmpty()) {
            return false;
        }
        List<Expression> inputs = new ArrayList<>();
        if (call instanceof MethodCallExpr m) {
            m.getScope().ifPresent(inputs::add);
            inputs.addAll(m.getArguments());
        } else if (call instanceof ObjectCreationExpr o) {
            inputs.addAll(o.getArguments());
        }
        for (Expression input : inputs) {
            if (isSelenium(typeOf(input, types.get()))) {
                return true;
            }
        }
        Node parent = call.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            if (parent instanceof CastExpr cast && cast.getType().isClassOrInterfaceType()
                    && isSelenium(types.get().qualify(cast.getType().asClassOrInterfaceType()).orElse(null))) {
                return true;
            }
            parent = parent.getParentNode().orElse(null);
        }
        if (parent instanceof VariableDeclarator v && v.getType().isClassOrInterfaceType()) {
            return isSelenium(types.get().qualify(v.getType().asClassOrInterfaceType()).orElse(null));
        }
        if (parent instanceof MethodCallExpr outer && outer.getScope().map(s -> s == call).orElse(false)) {
            return types.get().declaringType(outer).map(SeleniumTypes::isSelenium)
                    .orElseGet(() -> SeleniumApiCatalog.isKnownMethodName(outer.getNameAsString()));
        }
        return false;
    }

    private static String typeOf(Expression e, JavaTypes types) {
        Expression inner = e;
        while (inner instanceof EnclosedExpr enclosed) {
            inner = enclosed.getInner();
        }
        if (inner instanceof CastExpr cast && cast.getType().isClassOrInterfaceType()) {
            return types.qualify(cast.getType().asClassOrInterfaceType()).orElse(null);
        }
        try {
            ResolvedType type = inner.calculateResolvedType();
            return type.isReferenceType() ? type.asReferenceType().getQualifiedName() : null;
        } catch (RuntimeException | StackOverflowError ex) {
            if (inner instanceof NameExpr name) {
                return types.declaredType(name).orElse(null);
            }
            if (inner instanceof MethodCallExpr m) {
                return null; // unresolvable call result: unknown, not assumed Selenium
            }
            return null;
        }
    }

    private static boolean isSelenium(String qualifiedName) {
        return qualifiedName != null && qualifiedName.startsWith("org.openqa.selenium.");
    }
}

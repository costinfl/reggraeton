package io.reggraeton.scan.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.Optional;

/**
 * Finds the statement that opens a JavaScript dialog, for Selenium code that handles the dialog
 * afterwards. Playwright must know the answer before the dialog opens, so {@code alert.dismiss()} and
 * {@code alert.sendKeys(..)} need {@code Dialogs.expectDismiss()} / {@code expectPrompt(..)} inserted
 * before that statement. Only the simple, common shape is recognised: in the same block,
 * <pre>
 *   page.clickDelete();                                   // the trigger
 *   wait.until(ExpectedConditions.alertIsPresent());      // optional
 *   Alert alert = driver.switchTo().alert();              // or used inline
 *   alert.dismiss();
 * </pre>
 */
public final class DialogTriggers {

    private DialogTriggers() {
    }

    /** The statement opening the dialog that {@code alertCall} (dismiss/sendKeys/accept on an Alert) handles. */
    public static Optional<Statement> triggerFor(MethodCallExpr alertCall) {
        Optional<Expression> receiver = alertCall.getScope();
        if (receiver.isEmpty()) {
            return Optional.empty();
        }
        Optional<Statement> obtain = Optional.empty();
        Expression r = receiver.get();
        if (r instanceof MethodCallExpr call && obtainsAlert(call)) {
            obtain = call.findAncestor(Statement.class);
        } else if (r instanceof NameExpr name) {
            obtain = declarationOf(alertCall, name.getNameAsString())
                    .filter(v -> v.getInitializer().map(DialogTriggers::containsAlertLookup).orElse(false))
                    .flatMap(v -> v.findAncestor(Statement.class));
        }
        if (obtain.isEmpty() || !(obtain.get().getParentNode().orElse(null) instanceof BlockStmt block)) {
            return Optional.empty();
        }
        // the handling call must run unconditionally after the lookup: same block, not inside an if/loop
        Optional<Statement> handling = alertCall.findAncestor(Statement.class);
        if (handling.isEmpty() || handling.get().getParentNode().orElse(null) != block) {
            return Optional.empty();
        }
        int i = block.getStatements().indexOf(obtain.get());
        for (int j = i - 1; j >= 0; j--) {
            Statement previous = block.getStatement(j);
            if (containsAlertLookup(previous)) {
                continue; // wait.until(alertIsPresent()) and the like
            }
            if (previous.findFirst(MethodCallExpr.class).isPresent() || previous.findFirst(ObjectCreationExpr.class).isPresent()) {
                return Optional.of(previous);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code text} can be evaluated before the trigger statement: a literal, a constant, a
     * parameter or a field (not a local variable that may be assigned in between).
     */
    public static boolean evaluableEarly(Expression text) {
        if (text instanceof LiteralExpr || text instanceof FieldAccessExpr) {
            return true;
        }
        if (text instanceof NameExpr name) {
            Optional<VariableDeclarator> local = declarationOf(text, name.getNameAsString());
            return local.isEmpty() || local.get().findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class).isPresent();
        }
        return false;
    }

    private static boolean obtainsAlert(MethodCallExpr call) {
        return containsAlertLookup(call);
    }

    private static boolean containsAlertLookup(Node node) {
        return node.findAll(MethodCallExpr.class).stream().anyMatch(m -> m.getNameAsString().equals("alertIsPresent")
                || (m.getNameAsString().equals("alert") && m.getArguments().isEmpty()
                && m.getScope().map(s -> s instanceof MethodCallExpr sc && sc.getNameAsString().equals("switchTo")).orElse(false)));
    }

    /** Local variable declared in the enclosing method / lambda, if any. */
    private static Optional<VariableDeclarator> declarationOf(Node from, String name) {
        Node scope = from.findAncestor(LambdaExpr.class).map(n -> (Node) n)
                .or(() -> from.findAncestor(CallableDeclaration.class).map(n -> (Node) n))
                .orElse(null);
        if (scope == null) {
            return Optional.empty();
        }
        return scope.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name))
                .filter(v -> v.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class).isEmpty())
                .findFirst();
    }
}

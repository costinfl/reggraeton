package io.reggraeton.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * native mode: base class of the generated JUnit tests. Each test calls {@link #scenario} first,
 * then runs Before hooks, steps, and registers After hooks, mirroring what Cucumber did.
 */
public abstract class RegressionTest {

    /** A step or hook body. */
    @FunctionalInterface
    public interface Action {
        void run() throws Throwable;
    }

    /** Raised when a step fails; the message names the Gherkin step. */
    public static final class StepFailure extends AssertionError {
        private static final long serialVersionUID = 1L;

        StepFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ScenarioScope scope;
    private Scenario scenario;
    private final List<Action> afterHooks = new ArrayList<>();

    @RegisterExtension
    final AfterTestExecutionCallback finisher = this::finish;

    /** Starts the scenario: applies the tag filter, opens a fresh browser context and page. */
    protected final void scenario(String id, String name, String... tags) {
        List<String> tagList = List.of(tags);
        Assumptions.assumeTrue(TagFilter.accepts(tagList),
                () -> "excluded by tag expression '" + TagFilter.expression() + "'");
        scenario = new Scenario(id, name, tagList);
        scope = new ScenarioScope();
        scope.register(Scenario.class, scenario);
        PlaywrightSession.start(name + " (" + id + ")");
    }

    /** The glue object (step definitions, hooks, page objects...) of type {@code type} for this test. */
    protected final <T> T glue(Class<T> type) {
        return scope.get(type);
    }

    protected final Scenario currentScenario() {
        return scenario;
    }

    protected final void before(Action hook) throws Throwable {
        hook.run();
    }

    /** Registers an After hook; After hooks run after the test, also when it failed. */
    protected final void after(Action hook) {
        afterHooks.add(hook);
    }

    protected final void step(String text, Action action) {
        try {
            action.run();
        } catch (StepFailure e) {
            throw e;
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            throw new StepFailure("Step failed: " + text + System.lineSeparator() + message, e);
        }
    }

    /** For steps without (or with more than one) matching step definition. */
    protected static void undefined(String reason) {
        throw new AssertionError(reason);
    }

    private void finish(ExtensionContext context) throws Exception {
        if (scenario == null) {
            return; // skipped by the tag filter
        }
        boolean failed = context.getExecutionException().isPresent();
        if (failed) {
            scenario.markFailed();
        }
        Throwable afterFailure = null;
        for (Action hook : afterHooks) {
            try {
                hook.run();
            } catch (Throwable e) {
                if (afterFailure == null) {
                    afterFailure = e;
                } else {
                    afterFailure.addSuppressed(e);
                }
            }
        }
        afterHooks.clear();
        PlaywrightSession.finish(failed || afterFailure != null);
        if (afterFailure != null && !failed) {
            if (afterFailure instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError("After hook failed", afterFailure);
        }
    }
}

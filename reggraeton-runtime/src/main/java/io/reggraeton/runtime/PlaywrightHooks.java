package io.reggraeton.runtime;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

/**
 * bdd mode: opens a fresh browser context and page before every scenario (before any other
 * hook) and closes it after every scenario (after all other hooks), attaching a screenshot to
 * the Cucumber report when the scenario failed.
 */
public class PlaywrightHooks {

    /** Runs before all other Before hooks (they run in ascending order). */
    @Before(order = Integer.MIN_VALUE + 1)
    public void startPlaywright(Scenario scenario) {
        String uri = String.valueOf(scenario.getUri());
        PlaywrightSession.start(scenario.getName() + " (" + uri.substring(uri.lastIndexOf('/') + 1) + ":" + scenario.getLine() + ")");
    }

    /** Runs after all other After hooks (they run in descending order). */
    @After(order = Integer.MIN_VALUE + 1)
    public void stopPlaywright(Scenario scenario) {
        if (!PlaywrightSession.isActive()) {
            return;
        }
        if (scenario.isFailed()) {
            try {
                scenario.attach(PlaywrightSession.current().page().screenshot(), "image/png", "failure");
            } catch (RuntimeException e) {
                // page already gone
            }
        }
        PlaywrightSession.finish(scenario.isFailed());
    }
}

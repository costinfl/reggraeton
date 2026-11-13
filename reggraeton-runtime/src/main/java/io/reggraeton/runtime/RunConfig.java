package io.reggraeton.runtime;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Run settings of the generated tests. Each setting is read from a system property first
 * ({@code mvn test -DbaseUrl=...}), then from an environment variable (handy in Jenkins), then
 * falls back to its default.
 *
 * <table>
 *   <tr><th>Setting</th><th>System property</th><th>Environment</th><th>Default</th></tr>
 *   <tr><td>application URL</td><td>baseUrl</td><td>BASE_URL</td><td>http://localhost:8080</td></tr>
 *   <tr><td>headless / windowed</td><td>headless</td><td>HEADLESS</td><td>true</td></tr>
 *   <tr><td>installed browser</td><td>browser.channel</td><td>BROWSER_CHANNEL</td><td>msedge</td></tr>
 *   <tr><td>browser binary</td><td>browser.executable</td><td>BROWSER_EXECUTABLE</td><td>(none)</td></tr>
 *   <tr><td>slow motion (ms)</td><td>slowMo</td><td>SLOW_MO</td><td>0</td></tr>
 *   <tr><td>action/assert timeout (ms)</td><td>timeout</td><td>PW_TIMEOUT</td><td>15000</td></tr>
 *   <tr><td>navigation timeout (ms)</td><td>navigationTimeout</td><td>PW_NAVIGATION_TIMEOUT</td><td>30000</td></tr>
 *   <tr><td>default answer to JS dialogs</td><td>dialogs</td><td>DIALOGS</td><td>accept (dismiss)</td></tr>
 *   <tr><td>implicit wait for element lists (ms)</td><td>implicitWait</td><td>IMPLICIT_WAIT</td><td>0</td></tr>
 *   <tr><td>viewport</td><td>viewport</td><td>VIEWPORT</td><td>1366x768</td></tr>
 *   <tr><td>trace</td><td>trace</td><td>PW_TRACE</td><td>on-failure (on, off)</td></tr>
 *   <tr><td>artifacts folder</td><td>artifacts.dir</td><td>ARTIFACTS_DIR</td><td>target/playwright-artifacts</td></tr>
 * </table>
 */
public final class RunConfig {

    private RunConfig() {
    }

    public static String baseUrl() {
        String url = get("baseUrl", "BASE_URL", "http://localhost:8080");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static boolean headless() {
        return Boolean.parseBoolean(get("headless", "HEADLESS", "true"));
    }

    /** {@code msedge} (default), {@code chrome}, {@code chromium} (the browser Playwright finds in PLAYWRIGHT_BROWSERS_PATH) ... */
    public static String browserChannel() {
        return get("browser.channel", "BROWSER_CHANNEL", "msedge").trim().toLowerCase(Locale.ROOT);
    }

    public static Optional<Path> browserExecutable() {
        String value = get("browser.executable", "BROWSER_EXECUTABLE", "");
        return value.isBlank() ? Optional.empty() : Optional.of(Path.of(value.trim()));
    }

    public static double slowMo() {
        return Double.parseDouble(get("slowMo", "SLOW_MO", "0"));
    }

    public static double timeoutMs() {
        return Double.parseDouble(get("timeout", "PW_TIMEOUT", "15000"));
    }

    public static double navigationTimeoutMs() {
        return Double.parseDouble(get("navigationTimeout", "PW_NAVIGATION_TIMEOUT", "30000"));
    }

    /**
     * Like Selenium's implicit wait, but only for element lists ({@code findElements}, {@code @FindBy List}):
     * how long to wait for at least one match before returning an empty list. Single elements always auto-wait.
     */
    public static double implicitWaitMs() {
        return Double.parseDouble(get("implicitWait", "IMPLICIT_WAIT", "0"));
    }

    /** Viewport as {@code [width, height]}. */
    public static int[] viewport() {
        String value = get("viewport", "VIEWPORT", "1366x768").toLowerCase(Locale.ROOT);
        String[] parts = value.split("x");
        if (parts.length != 2) {
            throw new IllegalArgumentException("viewport must look like 1366x768, was " + value);
        }
        return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }

    /** {@code on-failure} (default), {@code on} or {@code off}. */
    public static String trace() {
        return get("trace", "PW_TRACE", "on-failure").trim().toLowerCase(Locale.ROOT);
    }

    public static Path artifactsDir() {
        return Path.of(get("artifacts.dir", "ARTIFACTS_DIR", "target/playwright-artifacts"));
    }

    /** Human-readable summary, printed once per run. */
    public static String describe() {
        return "baseUrl=" + baseUrl()
                + ", browser=" + browserExecutable().map(Path::toString).orElse(browserChannel())
                + ", headless=" + headless()
                + ", trace=" + trace();
    }

    static String get(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(environment);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return defaultValue;
    }
}

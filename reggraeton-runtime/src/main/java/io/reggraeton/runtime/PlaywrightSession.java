package io.reggraeton.runtime;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Tracing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One browser context and page per test, on a browser that is launched once per thread and
 * reused. Only browsers that are already installed are used: the session never downloads one.
 *
 * <p>Typical use: {@code PlaywrightSession.start(name)} before a test, {@code PlaywrightSession.current().page()}
 * from the test code, {@code PlaywrightSession.finish(failed)} after it.</p>
 */
public final class PlaywrightSession {

    private static final ThreadLocal<PlaywrightSession> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Engine> ENGINE = new ThreadLocal<>();
    private static final List<Engine> ALL_ENGINES = new ArrayList<>();
    private static volatile boolean announced;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(PlaywrightSession::closeAll, "playwright-session-shutdown"));
    }

    /** Playwright + browser of one thread. */
    private record Engine(Playwright playwright, Browser browser) {
    }

    private final String testName;
    private final BrowserContext context;
    private final Page page;
    private final boolean tracing;

    private PlaywrightSession(String testName, BrowserContext context, Page page, boolean tracing) {
        this.testName = testName;
        this.context = context;
        this.page = page;
        this.tracing = tracing;
    }

    /** Starts a fresh browser context and page for {@code testName} on the current thread. */
    public static PlaywrightSession start(String testName) {
        if (CURRENT.get() != null) {
            finish(false);
        }
        Engine engine = engine();
        int[] viewport = RunConfig.viewport();
        BrowserContext context = engine.browser().newContext(new Browser.NewContextOptions()
                .setBaseURL(RunConfig.baseUrl())
                .setViewportSize(viewport[0], viewport[1]));
        Dialogs.attach(context);
        context.setDefaultTimeout(RunConfig.timeoutMs());
        context.setDefaultNavigationTimeout(RunConfig.navigationTimeoutMs());
        boolean tracing = !"off".equals(RunConfig.trace());
        if (tracing) {
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true)
                    .setTitle(testName));
        }
        PlaywrightSession session = new PlaywrightSession(testName, context, context.newPage(), tracing);
        CURRENT.set(session);
        return session;
    }

    /** The session of the test running on this thread. */
    public static PlaywrightSession current() {
        PlaywrightSession session = CURRENT.get();
        if (session == null) {
            throw new IllegalStateException("No Playwright session on this thread. Tests must run through the "
                    + "generated hooks / base class, which call PlaywrightSession.start(...).");
        }
        return session;
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * Ends the current test: on failure saves a screenshot (and the trace when tracing is
     * {@code on-failure}), always saves the trace when tracing is {@code on}; closes the context.
     *
     * @return the artifact files written
     */
    public static List<Path> finish(boolean failed) {
        PlaywrightSession session = CURRENT.get();
        CURRENT.remove();
        if (session == null) {
            return List.of();
        }
        List<Path> artifacts = new ArrayList<>();
        try {
            String base = safeFileName(session.testName);
            Path dir = RunConfig.artifactsDir();
            if (failed) {
                Files.createDirectories(dir);
                Path png = dir.resolve(base + ".png");
                try {
                    session.page.screenshot(new Page.ScreenshotOptions().setPath(png).setFullPage(true));
                    artifacts.add(png);
                } catch (PlaywrightException e) {
                    // page already closed or crashed: nothing to capture
                }
            }
            if (session.tracing) {
                boolean keep = failed || "on".equals(RunConfig.trace());
                if (keep) {
                    Files.createDirectories(dir);
                    Path zip = dir.resolve(base + "-trace.zip");
                    session.context.tracing().stop(new Tracing.StopOptions().setPath(zip));
                    artifacts.add(zip);
                } else {
                    session.context.tracing().stop();
                }
            }
        } catch (java.io.IOException | PlaywrightException e) {
            System.err.println("[reggraeton] could not save artifacts for " + session.testName + ": " + e.getMessage());
        } finally {
            try {
                session.context.close();
            } catch (PlaywrightException e) {
                // already closed
            }
        }
        if (!artifacts.isEmpty()) {
            System.err.println("[reggraeton] artifacts for '" + session.testName + "': " + artifacts
                    + (artifacts.stream().anyMatch(p -> p.toString().endsWith(".zip"))
                    ? " (view a trace by dropping the zip on https://trace.playwright.dev; it stays on your machine)" : ""));
        }
        return artifacts;
    }

    public Page page() {
        return page;
    }

    public BrowserContext context() {
        return context;
    }

    public String testName() {
        return testName;
    }

    // ----------------------------------------------------------------------------------------

    private static Engine engine() {
        Engine engine = ENGINE.get();
        if (engine != null && engine.browser().isConnected()) {
            return engine;
        }
        if (!announced) {
            announced = true;
            System.err.println("[reggraeton] " + RunConfig.describe());
        }
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(RunConfig.headless())
                .setSlowMo(RunConfig.slowMo());
        String browserDescription;
        if (RunConfig.browserExecutable().isPresent()) {
            options.setExecutablePath(RunConfig.browserExecutable().get());
            browserDescription = "executable " + RunConfig.browserExecutable().get();
        } else {
            String channel = RunConfig.browserChannel();
            if (!channel.isEmpty() && !"bundled".equals(channel)) {
                options.setChannel(channel);
            }
            browserDescription = "channel '" + channel + "'";
        }
        Browser browser;
        try {
            browser = playwright.chromium().launch(options);
        } catch (PlaywrightException e) {
            playwright.close();
            throw new IllegalStateException("Could not start the installed browser (" + browserDescription + "). "
                    + "reggraeton never downloads browsers: install Microsoft Edge, choose another installed "
                    + "Chromium-based browser with -Dbrowser.channel=chrome|chromium, or point to a binary with "
                    + "-Dbrowser.executable=<path>. Cause: " + firstLine(e.getMessage()), e);
        }
        engine = new Engine(playwright, browser);
        ENGINE.set(engine);
        synchronized (ALL_ENGINES) {
            ALL_ENGINES.add(engine);
        }
        return engine;
    }

    private static void closeAll() {
        synchronized (ALL_ENGINES) {
            for (Engine engine : ALL_ENGINES) {
                try {
                    engine.playwright().close();
                } catch (RuntimeException e) {
                    // shutting down anyway
                }
            }
            ALL_ENGINES.clear();
        }
    }

    static String safeFileName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}

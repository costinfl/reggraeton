package io.reggraeton.cli;

import io.reggraeton.scan.java.SeleniumApiCatalog;
import picocli.CommandLine.Command;

import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/** {@code reggraeton rules}: prints the Selenium API catalogue (what is translated automatically) as Markdown. */
@Command(name = "rules", mixinStandardHelpOptions = true,
        description = "Prints, as Markdown, which Selenium APIs are translated automatically and which need manual work.")
public final class RulesCommand implements Callable<Integer> {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        out.print(markdown());
        out.flush();
        return 0;
    }

    static String markdown() {
        Map<String, SeleniumApiCatalog.Support> entries = new TreeMap<>(SeleniumApiCatalog.entries());
        StringBuilder md = new StringBuilder();
        md.append("# Selenium to Playwright translation rules\n\n");
        md.append("Generated with `reggraeton rules`. **auto**: rewritten by `convert`. **manual**: replaced by a\n");
        md.append("`TODO(reggraeton)` stub that throws `PendingConversionException` until a human rewrites it.\n");
        md.append("Calls into libraries outside the scanned repository are always manual. Driver creation\n");
        md.append("(`new ChromeDriver(..)`, browser options, WebDriverManager) is replaced by the generated\n");
        md.append("`PlaywrightSession`; JUnit/AssertJ/Hamcrest/TestNG assertions are kept as they are.\n\n");
        for (boolean auto : new boolean[] {true, false}) {
            md.append(auto ? "## Translated automatically\n\n" : "## Manual conversion\n\n");
            md.append("| Selenium API | Playwright |\n|---|---|\n");
            entries.forEach((api, support) -> {
                if (support.auto() == auto) {
                    md.append("| `").append(api).append("` | ").append(support.note().replace("|", "\\|")).append(" |\n");
                }
            });
            md.append('\n');
        }
        md.append("""
                ## Behaviour notes

                - `findElement` becomes `locator(..)` plus `.first()` (Selenium returns the first match;
                  Playwright locators are strict), except for `#id` selectors.
                - `findElements` and `@FindBy List<WebElement>` wait for the page load (as Selenium does after a
                  navigating click) and, with `-DimplicitWait=<ms>`, for a first match, like an implicit wait.
                - `clear(); sendKeys(text)` becomes `fill(text)`; other `sendKeys` keep Selenium semantics
                  (append at the end, `Keys`, chords) through `Elements.sendKeys`.
                - `WebDriverWait`/`FluentWait` become `Waits`, which honours the original timeout.
                - Base-URL lookups such as `System.getProperty("app.url", ...)` become `RunConfig.baseUrl()`, so
                  `-DbaseUrl` / `BASE_URL` controls the application under test.
                """);
        return md.toString();
    }
}

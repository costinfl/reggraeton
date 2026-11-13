package io.reggraeton.translate;

import java.util.Map;
import java.util.Set;

/** Names of the runtime support classes inside a generated project and of the Playwright types used. */
public final class SupportNames {

    /** Support classes copied from {@code reggraeton-runtime} into every generated project. */
    public static final Set<String> RUNTIME_CLASSES = Set.of(
            "Waits", "Locators", "PendingConversion", "PendingConversionException", "PlaywrightSession",
            "RunConfig", "Keys", "Elements", "Screenshots", "Dialogs", "Alert",
            "Scripts", "Cookies", "Cookie", "Selects");

    /** Additional support classes of native-mode projects (no Cucumber at run time). */
    public static final Set<String> NATIVE_CLASSES = Set.of(
            "RegressionTest", "Scenario", "DataTable", "ScenarioScope", "TagFilter");

    /** Additional support classes of bdd-mode projects. */
    public static final Set<String> BDD_CLASSES = Set.of("PlaywrightHooks");

    private static final Map<String, String> PLAYWRIGHT = Map.of(
            "Page", "com.microsoft.playwright.Page",
            "Locator", "com.microsoft.playwright.Locator",
            "BrowserContext", "com.microsoft.playwright.BrowserContext",
            "PlaywrightException", "com.microsoft.playwright.PlaywrightException",
            "TimeoutError", "com.microsoft.playwright.TimeoutError",
            "SelectOption", "com.microsoft.playwright.options.SelectOption",
            "MouseButton", "com.microsoft.playwright.options.MouseButton");

    private final String supportPackage;

    public SupportNames(String supportPackage) {
        this.supportPackage = supportPackage;
    }

    public String supportPackage() {
        return supportPackage;
    }

    /** Import for a simple name introduced by the translation, or null. */
    String importFor(String simpleName) {
        if (RUNTIME_CLASSES.contains(simpleName) || NATIVE_CLASSES.contains(simpleName)) {
            return supportPackage + "." + simpleName;
        }
        return PLAYWRIGHT.get(simpleName);
    }
}

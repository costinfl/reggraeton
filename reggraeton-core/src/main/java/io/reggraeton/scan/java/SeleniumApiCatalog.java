package io.reggraeton.scan.java;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The list of Selenium APIs reggraeton knows how to translate to Playwright ({@code auto}) and of
 * those that need a human ({@code manual}), with a short note for the report.
 *
 * <p>The translation rule engine must cover every {@code auto} entry; a unit test keeps the two in sync.</p>
 */
public final class SeleniumApiCatalog {

    /** Classification of one call site. */
    public record Support(boolean auto, String note) {
        static Support auto(String note) {
            return new Support(true, note);
        }

        static Support manual(String note) {
            return new Support(false, note);
        }
    }

    private static final Map<String, Support> ENTRIES = new HashMap<>();
    private static final Set<String> METHOD_NAMES = new HashSet<>();

    static {
        // WebDriver / SearchContext / WebElement
        auto("WebDriver.get", "page.navigate(url)");
        auto("WebDriver.getTitle", "page.title()");
        auto("WebDriver.getCurrentUrl", "page.url()");
        auto("WebDriver.getPageSource", "page.content()");
        auto("WebDriver.findElement", "page.locator(selector)");
        auto("WebDriver.findElements", "Elements.findAll(page.locator(selector))");
        auto("WebDriver.quit", "handled by the Playwright session");
        auto("WebDriver.close", "page.close()");
        auto("WebDriver.navigate", "see WebDriver.Navigation");
        auto("WebDriver.manage", "see WebDriver.Options");
        auto("WebDriver.switchTo", "see WebDriver.TargetLocator");
        manual("WebDriver.getWindowHandle", "windows: keep a reference to the Page object instead of a handle");
        manual("WebDriver.getWindowHandles", "multiple windows: use context.pages() / page.waitForPopup()");
        auto("SearchContext.findElement", "locator.locator(selector)");
        auto("SearchContext.findElements", "locator.locator(selector).all()");
        auto("WebElement.findElement", "locator.locator(selector)");
        auto("WebElement.getScreenshotAs", "locator.screenshot()");
        auto("WebElement.findElements", "locator.locator(selector).all()");
        auto("WebElement.click", "locator.click()");
        auto("WebElement.submit", "locator.press(\"Enter\")");
        auto("WebElement.sendKeys", "locator.fill(text) / locator.press(key)");
        auto("WebElement.clear", "locator.clear()");
        auto("WebElement.getText", "locator.innerText()");
        auto("WebElement.getAttribute", "locator.getAttribute(name)");
        auto("WebElement.getDomAttribute", "locator.getAttribute(name)");
        auto("WebElement.getDomProperty", "locator.evaluate(\"(e, p) => e[p]\", name)");
        auto("WebElement.getCssValue", "locator.evaluate(getComputedStyle)");
        auto("WebElement.isDisplayed", "locator.isVisible()");
        auto("WebElement.isEnabled", "locator.isEnabled()");
        auto("WebElement.isSelected", "locator.isChecked()");
        auto("WebElement.getTagName", "locator.evaluate(\"e => e.tagName.toLowerCase()\")");
        manual("WebElement.getSize", "use locator.boundingBox() (width/height fields)");
        manual("WebElement.getLocation", "use locator.boundingBox() (x/y fields)");
        manual("WebElement.getRect", "use locator.boundingBox()");

        // Locators
        for (String by : List.of("id", "cssSelector", "xpath", "name", "className", "linkText", "partialLinkText", "tagName")) {
            auto("By." + by, "Playwright selector");
        }
        manual("By.ByChained.<init>", "chained locators: rewrite as locator(a).locator(b)");
        auto("@FindBy", "Locator field");
        auto("@FindBys", "chained Locator field");
        auto("@FindAll", "Locator.or(...) field");
        auto("@CacheLookup", "dropped");
        auto("PageFactory.initElements", "dropped (locators are lazy in Playwright)");

        // Navigation, options, windows, timeouts, cookies
        auto("WebDriver.Navigation.to", "page.navigate(url)");
        auto("WebDriver.Navigation.back", "page.goBack()");
        auto("WebDriver.Navigation.forward", "page.goForward()");
        auto("WebDriver.Navigation.refresh", "page.reload()");
        auto("WebDriver.Options.window", "see WebDriver.Window");
        auto("WebDriver.Options.timeouts", "see WebDriver.Timeouts");
        auto("WebDriver.Options.deleteAllCookies", "page.context().clearCookies()");
        manual("WebDriver.Options.getCookies", "use page.context().cookies()");
        manual("WebDriver.Options.getCookieNamed", "use page.context().cookies()");
        manual("WebDriver.Options.addCookie", "use page.context().addCookies(...)");
        manual("WebDriver.Options.deleteCookieNamed", "use page.context().clearCookies(new ClearCookiesOptions().setName(..))");
        manual("WebDriver.Options.deleteCookie", "use page.context().clearCookies(...)");
        manual("WebDriver.Options.logs", "browser logs: subscribe to page.onConsoleMessage(...) instead");
        manual("Logs.get", "browser logs: subscribe to page.onConsoleMessage(...) instead");
        manual("LogEntries.getAll", "browser logs: subscribe to page.onConsoleMessage(...) instead");
        manual("LogEntries.iterator", "browser logs: subscribe to page.onConsoleMessage(...) instead");
        manual("LogEntries.filter", "browser logs: subscribe to page.onConsoleMessage(...) instead");
        auto("WebDriver.Window.maximize", "dropped (viewport is configured on the context)");
        auto("WebDriver.Window.fullscreen", "dropped (viewport is configured on the context)");
        auto("WebDriver.Window.setSize", "page.setViewportSize(w, h)");
        manual("WebDriver.Window.getSize", "use page.viewportSize()");
        manual("WebDriver.Window.setPosition", "window position is not controllable in Playwright");
        auto("WebDriver.Timeouts.implicitlyWait", "dropped (Playwright auto-waits)");
        auto("WebDriver.Timeouts.pageLoadTimeout", "page.setDefaultNavigationTimeout(ms)");
        auto("WebDriver.Timeouts.scriptTimeout", "dropped");
        auto("WebDriver.Timeouts.setScriptTimeout", "dropped");

        // Frames, windows, alerts
        auto("WebDriver.TargetLocator.activeElement", "page.locator(\"*:focus\")");
        manual("WebDriver.TargetLocator.frame", "frames: use page.frameLocator(selector) for the following calls");
        manual("WebDriver.TargetLocator.defaultContent", "frames: go back to using page");
        manual("WebDriver.TargetLocator.parentFrame", "frames: use the parent FrameLocator");
        manual("WebDriver.TargetLocator.window", "windows: use the Page object of the popup");
        manual("WebDriver.TargetLocator.newWindow", "windows: use context.newPage()");
        manual("WebDriver.TargetLocator.alert", "dialogs: register page.onceDialog(...) before the triggering action");
        manual("Alert.accept", "dialogs: register page.onceDialog(Dialog::accept) before the triggering action");
        manual("Alert.dismiss", "dialogs: register page.onceDialog(Dialog::dismiss) before the triggering action");
        manual("Alert.getText", "dialogs: read dialog.message() in page.onceDialog(...)");
        manual("Alert.sendKeys", "dialogs: dialog.accept(text) in page.onceDialog(...)");

        // JavaScript, screenshots
        auto("JavascriptExecutor.executeScript", "page.evaluate(script)");
        manual("JavascriptExecutor.executeAsyncScript", "rewrite the script to return a Promise and use page.evaluate");
        auto("TakesScreenshot.getScreenshotAs", "page.screenshot()");

        // Select
        auto("Select.<init>", "dropped; calls become locator.selectOption(...)");
        auto("Select.selectByVisibleText", "locator.selectOption(new SelectOption().setLabel(text))");
        auto("Select.selectByValue", "locator.selectOption(value)");
        auto("Select.selectByIndex", "locator.selectOption(new SelectOption().setIndex(i))");
        auto("Select.getFirstSelectedOption", "locator.locator(\"option:checked\").first()");
        auto("Select.getOptions", "Elements.findAll(locator.locator(\"option\"))");
        auto("Select.getAllSelectedOptions", "Elements.findAll(locator.locator(\"option:checked\"))");
        auto("Select.deselectAll", "locator.selectOption(new String[0])");
        auto("Select.getWrappedElement", "the select locator itself");
        manual("Select.deselectByVisibleText", "rewrite with locator.selectOption(...)");
        manual("Select.deselectByValue", "rewrite with locator.selectOption(...)");
        manual("Select.deselectByIndex", "rewrite with locator.selectOption(...)");
        auto("Select.isMultiple", "locator.getAttribute(\"multiple\") != null");

        // Actions
        auto("Actions.<init>", "action chain is rewritten as locator calls");
        auto("Actions.moveToElement", "locator.hover()");
        auto("Actions.click", "locator.click()");
        auto("Actions.doubleClick", "locator.dblclick()");
        auto("Actions.contextClick", "locator.click(new ClickOptions().setButton(RIGHT))");
        auto("Actions.dragAndDrop", "source.dragTo(target)");
        auto("Actions.sendKeys", "locator.pressSequentially(text) / page.keyboard().type(text)");
        auto("Actions.scrollToElement", "locator.scrollIntoViewIfNeeded()");
        auto("Actions.perform", "dropped");
        auto("Actions.build", "dropped");
        auto("Action.perform", "dropped");
        manual("Actions.keyDown", "use page.keyboard().down(key)");
        manual("Actions.keyUp", "use page.keyboard().up(key)");
        manual("Actions.moveByOffset", "use page.mouse().move(x, y)");
        manual("Actions.clickAndHold", "use page.mouse().down()");
        manual("Actions.release", "use page.mouse().up()");
        manual("Actions.pause", "remove, or use page.waitForTimeout(ms)");
        manual("Actions.dragAndDropBy", "use page.mouse() moves");

        // Waits
        auto("WebDriverWait.<init>", "dropped; waits become web-first assertions / waitFor");
        auto("FluentWait.<init>", "dropped; waits become web-first assertions / waitFor");
        auto("FluentWait.withTimeout", "timeout option on the generated wait");
        auto("FluentWait.pollingEvery", "dropped");
        auto("FluentWait.ignoring", "dropped");
        auto("FluentWait.withMessage", "dropped");
        auto("FluentWait.until", "translated from the ExpectedCondition argument");
        for (String condition : List.of("visibilityOfElementLocated", "visibilityOf", "elementToBeClickable",
                "presenceOfElementLocated", "invisibilityOfElementLocated", "invisibilityOf",
                "textToBePresentInElement", "textToBePresentInElementLocated", "textToBe",
                "titleIs", "titleContains", "urlContains", "urlToBe", "urlMatches",
                "numberOfElementsToBe", "elementToBeSelected", "attributeToBe",
                "visibilityOfAllElementsLocatedBy", "presenceOfAllElementsLocatedBy")) {
            auto("ExpectedConditions." + condition, "locator.waitFor / assertThat(...)");
        }
        manual("ExpectedConditions.alertIsPresent", "dialogs: register page.onceDialog(...)");
        manual("ExpectedConditions.frameToBeAvailableAndSwitchToIt", "frames: use page.frameLocator(selector)");
        manual("ExpectedConditions.stalenessOf", "rewrite with locator.waitFor(DETACHED)");
        manual("ExpectedConditions.numberOfWindowsToBe", "windows: use context.pages()");

        // Keys is an enum: its constants are handled as part of sendKeys
    }

    private SeleniumApiCatalog() {
    }

    private static void auto(String api, String note) {
        register(api, Support.auto(note));
    }

    private static void manual(String api, String note) {
        register(api, Support.manual(note));
    }

    private static void register(String api, Support support) {
        ENTRIES.put(api, support);
        METHOD_NAMES.add(api.substring(api.lastIndexOf('.') + 1));
    }

    /** All entries (api name to support), for documentation and consistency tests. */
    public static Map<String, Support> entries() {
        return Map.copyOf(ENTRIES);
    }

    /** Whether {@code methodName} is the name of any catalogued Selenium API method. */
    public static boolean isKnownMethodName(String methodName) {
        return METHOD_NAMES.contains(methodName);
    }

    /**
     * Classifies one call site. Some APIs are automatic only in their common form, e.g.
     * {@code executeScript} without element arguments or {@code until} with a known condition.
     */
    public static Support classify(String api, MethodCallExpr call) {
        Support support = ENTRIES.get(normalise(api));
        if (support == null) {
            return Support.manual("no translation rule for this Selenium API");
        }
        if (call != null && support.auto()) {
            if (api.endsWith("JavascriptExecutor.executeScript") && call.getArguments().size() > 1) {
                return Support.manual("executeScript with arguments: rewrite as locator.evaluate(\"el => ...\")");
            }
            if (api.endsWith(".until") && call.getArguments().size() == 1) {
                Expression condition = call.getArgument(0);
                if (!(condition instanceof MethodCallExpr m && isExpectedConditions(m))) {
                    return Support.manual("custom wait condition: rewrite with locator.waitFor / assertThat");
                }
                String name = ((MethodCallExpr) condition).getNameAsString();
                Support conditionSupport = ENTRIES.get("ExpectedConditions." + name);
                if (conditionSupport == null || !conditionSupport.auto()) {
                    return Support.manual("wait condition ExpectedConditions." + name + " has no translation rule");
                }
            }
        }
        return support;
    }

    private static boolean isExpectedConditions(MethodCallExpr call) {
        return call.getScope().map(s -> s instanceof NameExpr n && n.getNameAsString().equals("ExpectedConditions"))
                .orElse(true); // statically imported conditions
    }

    /** Maps implementation types onto the interface the catalogue uses. */
    public static String normalise(String api) {
        int dot = api.lastIndexOf('.');
        if (dot < 0) {
            return api;
        }
        String type = api.substring(0, dot);
        String member = api.substring(dot + 1);
        String mapped = switch (type) {
            case "RemoteWebDriver", "ChromeDriver", "EdgeDriver", "FirefoxDriver", "SafariDriver",
                 "ChromiumDriver", "InternetExplorerDriver" -> "WebDriver";
            case "RemoteWebElement" -> "WebElement";
            case "WebDriverWait" -> member.equals("<init>") ? "WebDriverWait" : "FluentWait";
            case "Wait" -> "FluentWait";
            default -> type;
        };
        return mapped + "." + member;
    }
}

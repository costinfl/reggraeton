# Selenium to Playwright translation rules

Generated with `reggraeton rules`. **auto**: rewritten by `convert`. **manual**: replaced by a
`TODO(reggraeton)` stub that throws `PendingConversionException` until a human rewrites it.
Calls into other libraries are kept unless Selenium objects pass through them. Driver creation
(`new ChromeDriver(..)`, browser options, WebDriverManager) is replaced by the generated
`PlaywrightSession`; JUnit/AssertJ/Hamcrest/TestNG assertions and database access
(JDBC, Spring, JPA/Hibernate, Oracle driver, SQL) are kept as they are.

## Translated automatically

| Selenium API | Playwright |
|---|---|
| `@CacheLookup` | dropped |
| `@FindAll` | Locator.or(...) field |
| `@FindBy` | Locator field |
| `@FindBys` | chained Locator field |
| `Action.perform` | dropped |
| `Actions.<init>` | action chain is rewritten as locator calls |
| `Actions.build` | dropped |
| `Actions.click` | locator.click() |
| `Actions.contextClick` | locator.click(new ClickOptions().setButton(RIGHT)) |
| `Actions.doubleClick` | locator.dblclick() |
| `Actions.dragAndDrop` | source.dragTo(target) |
| `Actions.moveToElement` | locator.hover() |
| `Actions.perform` | dropped |
| `Actions.scrollToElement` | locator.scrollIntoViewIfNeeded() |
| `Actions.sendKeys` | locator.pressSequentially(text) / page.keyboard().type(text) |
| `Alert.accept` | checks the recorded dialog was accepted (the default answer) |
| `Alert.dismiss` | Dialogs.expectDismiss() is inserted before the action that opens the dialog |
| `Alert.getText` | message of the recorded dialog |
| `Alert.sendKeys` | Dialogs.expectPrompt(text) is inserted before the action that opens the dialog |
| `Augmenter.<init>` | dropped |
| `Augmenter.augment` | the driver itself: Playwright pages take screenshots without augmenting |
| `By.className` | Playwright selector |
| `By.cssSelector` | Playwright selector |
| `By.id` | Playwright selector |
| `By.linkText` | Playwright selector |
| `By.name` | Playwright selector |
| `By.partialLinkText` | Playwright selector |
| `By.tagName` | Playwright selector |
| `By.xpath` | Playwright selector |
| `Cookie.<init>` | support Cookie with Selenium's constructors |
| `Cookie.getDomain` | same getter on the support Cookie |
| `Cookie.getExpiry` | same getter on the support Cookie |
| `Cookie.getName` | same getter on the support Cookie |
| `Cookie.getPath` | same getter on the support Cookie |
| `Cookie.getSameSite` | same getter on the support Cookie |
| `Cookie.getValue` | same getter on the support Cookie |
| `Cookie.isHttpOnly` | same getter on the support Cookie |
| `Cookie.isSecure` | same getter on the support Cookie |
| `ExpectedCondition.<init>` | anonymous condition becomes a java.util.function.Function<Page, T> |
| `ExpectedCondition.apply` | Function.apply |
| `ExpectedConditions.alertIsPresent` | wait.alert() |
| `ExpectedConditions.attributeToBe` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.elementToBeClickable` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.elementToBeSelected` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.invisibilityOf` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.invisibilityOfElementLocated` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.numberOfElementsToBe` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.presenceOfAllElementsLocatedBy` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.presenceOfElementLocated` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.textToBe` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.textToBePresentInElement` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.textToBePresentInElementLocated` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.titleContains` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.titleIs` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.urlContains` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.urlMatches` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.urlToBe` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.visibilityOf` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.visibilityOfAllElementsLocatedBy` | locator.waitFor / assertThat(...) |
| `ExpectedConditions.visibilityOfElementLocated` | locator.waitFor / assertThat(...) |
| `FluentWait.<init>` | dropped; waits become web-first assertions / waitFor |
| `FluentWait.ignoring` | dropped |
| `FluentWait.pollingEvery` | dropped |
| `FluentWait.until` | wait.<condition>(..) for ExpectedConditions, wait.until(page -> ..) for lambdas and custom conditions |
| `FluentWait.withMessage` | dropped |
| `FluentWait.withTimeout` | timeout option on the generated wait |
| `JavascriptExecutor.executeAsyncScript` | Scripts.executeAsync(page, script, args...): callback as last argument |
| `JavascriptExecutor.executeScript` | Scripts.execute(page, script, args...): arguments[], elements, return values as in Selenium |
| `Keys.chord` | support Keys.chord (same encoding); Elements.sendKeys presses it |
| `PageFactory.initElements` | dropped (locators are lazy in Playwright) |
| `SearchContext.findElement` | locator.locator(selector) |
| `SearchContext.findElements` | locator.locator(selector).all() |
| `Select.<init>` | dropped; calls become locator.selectOption(...) |
| `Select.deselectAll` | locator.selectOption(new String[0]) |
| `Select.deselectByIndex` | Selects.deselectByIndex(select, index) |
| `Select.deselectByValue` | Selects.deselectByValue(select, value) |
| `Select.deselectByVisibleText` | Selects.deselectByVisibleText(select, text) (multi-select only, like Selenium) |
| `Select.getAllSelectedOptions` | Elements.findAll(locator.locator("option:checked")) |
| `Select.getFirstSelectedOption` | locator.locator("option:checked").first() |
| `Select.getOptions` | Elements.findAll(locator.locator("option")) |
| `Select.getWrappedElement` | the select locator itself |
| `Select.isMultiple` | locator.getAttribute("multiple") != null |
| `Select.selectByIndex` | locator.selectOption(new SelectOption().setIndex(i)) |
| `Select.selectByValue` | locator.selectOption(value) |
| `Select.selectByVisibleText` | locator.selectOption(new SelectOption().setLabel(text)) |
| `TakesScreenshot.getScreenshotAs` | page.screenshot() |
| `WebDriver.Navigation.back` | page.goBack() |
| `WebDriver.Navigation.forward` | page.goForward() |
| `WebDriver.Navigation.refresh` | page.reload() |
| `WebDriver.Navigation.to` | page.navigate(url) |
| `WebDriver.Options.addCookie` | Cookies.add(page, cookie) |
| `WebDriver.Options.deleteAllCookies` | page.context().clearCookies() |
| `WebDriver.Options.deleteCookie` | Cookies.delete(page, cookie) |
| `WebDriver.Options.deleteCookieNamed` | Cookies.delete(page, name) |
| `WebDriver.Options.getCookieNamed` | Cookies.named(page, name) |
| `WebDriver.Options.getCookies` | Cookies.all(page) |
| `WebDriver.Options.timeouts` | see WebDriver.Timeouts |
| `WebDriver.Options.window` | see WebDriver.Window |
| `WebDriver.TargetLocator.activeElement` | page.locator("*:focus") |
| `WebDriver.TargetLocator.alert` | Dialogs.alert(page): dialogs are answered when they open and recorded |
| `WebDriver.Timeouts.implicitlyWait` | dropped (Playwright auto-waits) |
| `WebDriver.Timeouts.pageLoadTimeout` | page.setDefaultNavigationTimeout(ms) |
| `WebDriver.Timeouts.scriptTimeout` | dropped |
| `WebDriver.Timeouts.setScriptTimeout` | dropped |
| `WebDriver.Window.fullscreen` | dropped (viewport is configured on the context) |
| `WebDriver.Window.maximize` | dropped (viewport is configured on the context) |
| `WebDriver.Window.setSize` | page.setViewportSize(w, h) |
| `WebDriver.close` | page.close() |
| `WebDriver.findElement` | page.locator(selector) |
| `WebDriver.findElements` | Elements.findAll(page.locator(selector)) |
| `WebDriver.get` | page.navigate(url) |
| `WebDriver.getCurrentUrl` | page.url() |
| `WebDriver.getPageSource` | page.content() |
| `WebDriver.getTitle` | page.title() |
| `WebDriver.manage` | see WebDriver.Options |
| `WebDriver.navigate` | see WebDriver.Navigation |
| `WebDriver.quit` | handled by the Playwright session |
| `WebDriver.switchTo` | see WebDriver.TargetLocator |
| `WebDriverWait.<init>` | dropped; waits become web-first assertions / waitFor |
| `WebElement.clear` | locator.clear() |
| `WebElement.click` | locator.click() |
| `WebElement.findElement` | locator.locator(selector) |
| `WebElement.findElements` | locator.locator(selector).all() |
| `WebElement.getAttribute` | locator.getAttribute(name) |
| `WebElement.getCssValue` | locator.evaluate(getComputedStyle) |
| `WebElement.getDomAttribute` | locator.getAttribute(name) |
| `WebElement.getDomProperty` | locator.evaluate("(e, p) => e[p]", name) |
| `WebElement.getScreenshotAs` | locator.screenshot() |
| `WebElement.getTagName` | locator.evaluate("e => e.tagName.toLowerCase()") |
| `WebElement.getText` | locator.innerText() |
| `WebElement.isDisplayed` | locator.isVisible() |
| `WebElement.isEnabled` | locator.isEnabled() |
| `WebElement.isSelected` | locator.isChecked() |
| `WebElement.sendKeys` | locator.fill(text) / locator.press(key) |
| `WebElement.submit` | locator.press("Enter") |

## Manual conversion

| Selenium API | Playwright |
|---|---|
| `Actions.clickAndHold` | use page.mouse().down() |
| `Actions.dragAndDropBy` | use page.mouse() moves |
| `Actions.keyDown` | use page.keyboard().down(key) |
| `Actions.keyUp` | use page.keyboard().up(key) |
| `Actions.moveByOffset` | use page.mouse().move(x, y) |
| `Actions.pause` | remove, or use page.waitForTimeout(ms) |
| `Actions.release` | use page.mouse().up() |
| `By.ByChained.<init>` | chained locators: rewrite as locator(a).locator(b) |
| `ExpectedConditions.frameToBeAvailableAndSwitchToIt` | frames: use page.frameLocator(selector) |
| `ExpectedConditions.numberOfWindowsToBe` | windows: use context.pages() |
| `ExpectedConditions.stalenessOf` | rewrite with locator.waitFor(DETACHED) |
| `LogEntries.filter` | browser logs: subscribe to page.onConsoleMessage(...) instead |
| `LogEntries.getAll` | browser logs: subscribe to page.onConsoleMessage(...) instead |
| `LogEntries.iterator` | browser logs: subscribe to page.onConsoleMessage(...) instead |
| `Logs.get` | browser logs: subscribe to page.onConsoleMessage(...) instead |
| `WebDriver.Options.logs` | browser logs: subscribe to page.onConsoleMessage(...) instead |
| `WebDriver.TargetLocator.defaultContent` | frames: go back to using page |
| `WebDriver.TargetLocator.frame` | frames: use page.frameLocator(selector) for the following calls |
| `WebDriver.TargetLocator.newWindow` | windows: use context.newPage() |
| `WebDriver.TargetLocator.parentFrame` | frames: use the parent FrameLocator |
| `WebDriver.TargetLocator.window` | windows: use the Page object of the popup |
| `WebDriver.Window.getSize` | use page.viewportSize() |
| `WebDriver.Window.setPosition` | window position is not controllable in Playwright |
| `WebDriver.getWindowHandle` | windows: keep a reference to the Page object instead of a handle |
| `WebDriver.getWindowHandles` | multiple windows: use context.pages() / page.waitForPopup() |
| `WebElement.getLocation` | use locator.boundingBox() (x/y fields) |
| `WebElement.getRect` | use locator.boundingBox() |
| `WebElement.getSize` | use locator.boundingBox() (width/height fields) |

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

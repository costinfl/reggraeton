package io.reggraeton.translate;

import io.reggraeton.Samples;
import io.reggraeton.TempRepo;
import io.reggraeton.scan.Assessor;
import io.reggraeton.scan.RepositoryScanner;
import io.reggraeton.scan.java.JavaSourceIndex;
import io.reggraeton.scan.java.SeleniumApiCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorTest {

    private static final String SUPPORT = "regression.support";

    /** Translates every file of a throw-away repository; returns path to translated source. */
    private static Map<String, TranslatedSource> translate(Path repo) {
        return translate(repo, io.reggraeton.scan.java.ExternalPolicy.defaults());
    }

    private static Map<String, TranslatedSource> translate(Path repo, io.reggraeton.scan.java.ExternalPolicy policy) {
        JavaSourceIndex index = JavaSourceIndex.build(repo, List.of(), policy);
        Set<String> methods = new HashSet<>();
        index.types().values().forEach(t -> t.getMethods().forEach(m -> methods.add(m.getNameAsString())));
        Map<String, TranslatedSource> result = new LinkedHashMap<>();
        for (JavaSourceIndex.Source s : index.sources()) {
            TranslatedSource t = new SourceTranslator(s.unit(), s.path(), index, new SupportNames(SUPPORT), methods).translate();
            result.put(t.primaryType(), t);
        }
        return result;
    }

    private static String imports() {
        return """
                import org.openqa.selenium.*;
                import org.openqa.selenium.support.ui.*;
                import org.openqa.selenium.interactions.Actions;
                import java.time.Duration;
                import java.util.List;
                """;
    }

    @Test
    void translatesCoreWebDriverAndElementCalls(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page1", imports() + """
                public class Page1 {
                    private final WebDriver driver;
                    public Page1(WebDriver driver) { this.driver = driver; }
                    public String run(String user) {
                        driver.get("http://localhost/login");
                        driver.navigate().refresh();
                        WebElement name = driver.findElement(By.id("user"));
                        name.clear();
                        name.sendKeys(user);
                        name.sendKeys(Keys.TAB);
                        driver.findElement(By.name("q")).sendKeys("abc", Keys.ENTER);
                        List<WebElement> rows = driver.findElements(By.cssSelector("tr.row"));
                        String value = driver.findElement(By.xpath("//input[@id='" + user + "']")).getAttribute("value");
                        new Select(driver.findElement(By.id("sort"))).selectByValue("asc");
                        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
                        byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                        driver.manage().window().maximize();
                        boolean shown = driver.findElement(By.linkText("Cart")).isDisplayed();
                        return driver.getTitle() + rows.size() + value + shown + png.length;
                    }
                }
                """);

        String out = translate(dir).get("Page1").source();

        assertThat(out)
                .contains("private final Page driver;")
                .contains("driver.navigate(\"http://localhost/login\");")
                .contains("driver.reload();")
                .contains("Locator name = driver.locator(\"#user\");")
                .contains("name.fill(user);")
                .contains("name.press(\"Tab\");")
                .contains("Elements.sendKeys(driver.locator(\"[name=\\\"q\\\"]\").first(), \"abc\", Keys.ENTER);")
                .contains("List<Locator> rows = Elements.findAll(driver.locator(\"tr.row\"));")
                .contains("driver.locator(\"xpath=//input[@id='\" + user + \"']\").first().inputValue()")
                .contains("driver.locator(\"#sort\").selectOption(\"asc\");")
                .contains("driver.evaluate(\"() => { window.scrollTo(0, 0); }\");")
                .contains("byte[] png = driver.screenshot();")
                .contains("// reggraeton: removed driver.manage().window().maximize();")
                .contains("driver.locator(\"a:text-is(\\\"Cart\\\")\").first().isVisible()")
                .contains("return driver.title()")
                .contains("import com.microsoft.playwright.Page;")
                .contains("import regression.support.Keys;")
                .doesNotContain("org.openqa.selenium");
    }

    @Test
    void translatesWaitsActionsAndFindByFields(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page2", imports() + """
                import org.openqa.selenium.support.FindBy;
                import org.openqa.selenium.support.FindBys;
                import org.openqa.selenium.support.PageFactory;
                import static org.openqa.selenium.support.ui.ExpectedConditions.visibilityOf;

                public class Page2 {
                    private final WebDriver driver;
                    private final WebDriverWait wait;
                    @FindBy(css = ".menu") private WebElement menu;
                    @FindBy(id = "item") private WebElement item;
                    @FindBys({@FindBy(id = "list"), @FindBy(tagName = "li")}) private List<WebElement> entries;

                    public Page2(WebDriver driver) {
                        this.driver = driver;
                        this.wait = new WebDriverWait(driver, Duration.ofSeconds(5));
                        PageFactory.initElements(driver, this);
                    }

                    public void open() {
                        wait.until(visibilityOf(menu));
                        wait.until(ExpectedConditions.titleContains("Shop"));
                        new Actions(driver).moveToElement(menu).click(item).perform();
                        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.className("spinner")));
                    }
                }
                """);

        String out = translate(dir).get("Page2").source();

        assertThat(out)
                .contains("private Page driver;") // final dropped: the lazy locators read it from a lambda
                .contains("private Locator menu = Locators.lazy(() -> this.driver.locator(\".menu\").first());")
                .contains("private Locator item = Locators.lazy(() -> this.driver.locator(\"#item\"));")
                .contains("private List<Locator> entries = Locators.lazyAll(() -> this.driver.locator(\"#list\").locator(\"li\"));")
                .contains("this.wait = new Waits(driver, Duration.ofSeconds(5));")
                .contains("wait.visible(menu);")
                .contains("wait.titleContains(\"Shop\");")
                .contains("menu.hover();")
                .contains("item.click();")
                .contains("wait.hidden(\".spinner\");")
                .doesNotContain("@FindBy")
                .doesNotContain("org.openqa.selenium");
    }

    @Test
    void stubsWhatItCannotTranslateAndKeepsItCompilable(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page3", imports() + """
                import com.acme.qa.Evidence;

                public class Page3 {
                    private WebDriver driver;
                    public String accept() {
                        driver.switchTo().frame("payment");
                        Object cookies = driver.manage().getCookies();
                        String text = driver.getWindowHandle();
                        Evidence.attach(driver, "x");
                        try {
                            driver.findElement(By.id("ok")).click();
                        } catch (NoSuchElementException | TimeoutException e) {
                            return "missing";
                        }
                        return text;
                    }
                }
                """);

        TranslatedSource t = translate(dir).get("Page3");

        assertThat(t.todos()).extracting(TranslatedSource.Todo::api)
                .contains("WebDriver.TargetLocator.frame", "WebDriver.Options.getCookies", "WebDriver.getWindowHandle", "Evidence.attach");
        assertThat(t.source())
                .contains("// TODO(reggraeton) WebDriver.TargetLocator.frame")
                .contains("PendingConversion.fail(\"Page3.java:")
                .contains("Object cookies = PendingConversion.fail(")
                .contains("String text = PendingConversion.fail(")
                .contains("catch (PlaywrightException e)")
                .doesNotContain("org.openqa.selenium");
    }

    @Test
    void replacesDriverSetupAndBaseUrlConfiguration(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Drivers", imports() + """
                import org.openqa.selenium.chrome.ChromeDriver;
                import org.openqa.selenium.chrome.ChromeOptions;
                import io.github.bonigarcia.wdm.WebDriverManager;

                public final class Drivers {
                    public static WebDriver create() {
                        WebDriverManager.chromedriver().setup();
                        ChromeOptions options = new ChromeOptions();
                        options.addArguments("--headless=new");
                        WebDriver driver = new ChromeDriver(options);
                        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
                        driver.get(System.getProperty("base.url", "http://localhost:8080") + "/home");
                        return driver;
                    }
                }
                """);

        TranslatedSource t = translate(dir).get("Drivers");

        assertThat(t.todos()).isEmpty();
        assertThat(t.source())
                .contains("Page driver = PlaywrightSession.current().page();")
                .contains("driver.navigate(RunConfig.baseUrl() + \"/home\");")
                .contains("// reggraeton: removed WebDriverManager.chromedriver().setup();")
                .contains("// reggraeton: removed options.addArguments(\"--headless=new\");")
                .doesNotContain("bonigarcia")
                .doesNotContain("org.openqa.selenium");
    }

    @Test
    void handlesDialogsTheWayPlaywrightNeeds(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page5", imports() + """
                public class Page5 {
                    private WebDriver driver;
                    private WebDriverWait wait;
                    public String deleteAndConfirm() {
                        driver.findElement(By.id("delete")).click();
                        Alert alert = driver.switchTo().alert();
                        String text = alert.getText();
                        alert.accept();
                        return text;
                    }
                    public void deleteButCancel() {
                        driver.findElement(By.id("delete")).click();
                        wait.until(ExpectedConditions.alertIsPresent());
                        driver.switchTo().alert().dismiss();
                    }
                    public void rename() {
                        driver.findElement(By.id("rename")).click();
                        Alert prompt = wait.until(ExpectedConditions.alertIsPresent());
                        prompt.sendKeys("new name");
                        prompt.accept();
                    }
                    public void cancelWhateverIsOpen() {
                        driver.switchTo().alert().dismiss();
                    }
                    public void decide(boolean ok) {
                        driver.findElement(By.id("delete")).click();
                        Alert alert = driver.switchTo().alert();
                        if (ok) { alert.accept(); } else { alert.dismiss(); }
                    }
                }
                """);

        TranslatedSource t = translate(dir).get("Page5");

        assertThat(t.source())
                .contains("Alert alert = Dialogs.alert(driver);")
                .contains("String text = alert.getText();")
                .contains("alert.accept();")
                .contains("wait.alert();")
                .contains("Alert prompt = wait.alert();")
                .contains("prompt.sendKeys(\"new name\");");
        // dismiss / prompt answers are registered before the click that opens the dialog
        String cancel = t.source().substring(t.source().indexOf("void deleteButCancel"));
        assertThat(cancel.indexOf("Dialogs.expectDismiss();")).isGreaterThan(0)
                .isLessThan(cancel.indexOf("driver.locator(\"#delete\").click();"));
        String rename = t.source().substring(t.source().indexOf("void rename"));
        assertThat(rename.indexOf("Dialogs.expectPrompt(\"new name\");")).isGreaterThan(0)
                .isLessThan(rename.indexOf("driver.locator(\"#rename\").click();"));
        // no triggering action in the same method: left for a human, with the explanation
        // no triggering action in the same method, or a conditional dismiss: left for a human, with the explanation
        assertThat(t.todos()).hasSize(2).allSatisfy(todo -> {
            assertThat(todo.api()).isEqualTo("Alert.dismiss");
            assertThat(todo.note()).contains("Dialogs.expectDismiss()");
        });
    }

    @Test
    void keepsDropsOrFlagsExternalCalls(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page6", imports() + """
                import com.acme.log.Log;
                import com.acme.qa.Evidence;
                import com.acme.qa.Metrics;
                import com.acme.data.TestData;

                public class Page6 {
                    private WebDriver driver;
                    public void save() {
                        Log.info("saving");
                        String user = TestData.user("admin");
                        Metrics.count("save");
                        Evidence.attach(driver, "save");
                        driver.findElement(By.id("user")).sendKeys(user);
                    }
                }
                """);
        io.reggraeton.scan.java.ExternalPolicy policy = new io.reggraeton.scan.java.ExternalPolicy(
                List.of(), List.of("com.acme.qa.Metrics"), List.of("com.acme.data"), "test");

        TranslatedSource t = translate(dir, policy).get("Page6");

        assertThat(t.source())
                .contains("Log.info(\"saving\");")                           // kept: no Selenium involved
                .contains("// reggraeton: removed Metrics.count(\"save\");") // dropped by configuration
                .contains("import com.acme.log.Log;");
        assertThat(t.todos()).extracting(TranslatedSource.Todo::api)
                .containsExactlyInAnyOrder("TestData.user", "Evidence.attach");  // configured manual; receives the driver
    }

    @Test
    void translatesSelectQueries(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Page4", imports() + """
                public class Page4 {
                    private WebDriver driver;
                    public String read() {
                        Select country = new Select(driver.findElement(By.id("country")));
                        List<WebElement> options = country.getOptions();
                        String first = new Select(driver.findElement(By.id("city"))).getFirstSelectedOption().getText();
                        int selected = country.getAllSelectedOptions().size();
                        if (country.isMultiple()) {
                            country.deselectAll();
                        }
                        return options.get(0).getText() + first + selected + country.getWrappedElement().isEnabled();
                    }
                }
                """);

        TranslatedSource t = translate(dir).get("Page4");

        assertThat(t.todos()).isEmpty();
        assertThat(t.source())
                .contains("Locator country = driver.locator(\"#country\");")
                .contains("List<Locator> options = Elements.findAll(country.locator(\"option\"));")
                .contains("String first = driver.locator(\"#city\").locator(\"option:checked\").first().innerText();")
                .contains("int selected = Elements.findAll(country.locator(\"option:checked\")).size();")
                .contains("if ((country.getAttribute(\"multiple\") != null)) {")
                .contains("country.selectOption(new String[0]);")
                .contains("country.isEnabled()");
    }

    @Test
    void runtimeSourcesArePureAscii() {
        // copied into generated projects and compiled with whatever encoding the user's tools default to
        java.util.Set<String> all = new java.util.TreeSet<>(SupportNames.RUNTIME_CLASSES);
        all.addAll(SupportNames.NATIVE_CLASSES);
        all.addAll(SupportNames.BDD_CLASSES);
        RuntimeSources.load(SUPPORT, all).forEach((name, source) ->
                assertThat(source.chars().allMatch(c -> c < 128)).as(name + ".java is ASCII only").isTrue());
    }

    @Test
    void everyAutomaticCatalogueEntryHasARule() {
        Set<String> auto = SeleniumApiCatalog.entries().entrySet().stream()
                .filter(e -> e.getValue().auto())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(SourceTranslator.RULES).containsAll(auto);
    }

    @Test
    void translatedLegacyShopCompilesWithTheRuntime(@TempDir Path dir) {
        RepositoryScanner.Scan scan = new RepositoryScanner().scanWithSources(Samples.legacyShop());
        Translator.Result result = Translator.translate(scan.index(), scan.inventory(),
                Assessor.usedNodes(scan.inventory()), SUPPORT);

        Map<String, String> sources = new LinkedHashMap<>();
        result.sources().forEach(s -> sources.put(s.relativePath(), s.source()));
        RuntimeSources.load(SUPPORT).forEach((name, source) -> sources.put("regression/support/" + name + ".java", source));

        assertThat(result.sources()).extracting(TranslatedSource::primaryType)
                .contains("LoginSteps", "CartSteps", "SearchSteps", "Hooks", "LoginPage", "BasePage", "TestContext", "ParameterTypes")
                .doesNotContain("RunCucumberTest");
        assertThat(result.todos()).extracting(TranslatedSource.Todo::api)
                .containsExactlyInAnyOrder("Logs.get", "WebDriver.Options.logs", "LogEntries.getAll");
        assertThat(result.sources()).allSatisfy(s -> assertThat(s.source())
                .doesNotContain("import org.openqa.selenium").doesNotContain("bonigarcia"));

        List<String> errors = InMemoryCompiler.compile(sources, dir);
        assertThat(errors).as("compiler errors").isEmpty();
    }
}

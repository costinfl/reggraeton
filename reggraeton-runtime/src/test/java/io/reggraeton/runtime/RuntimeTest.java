package io.reggraeton.runtime;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeTest {

    @AfterEach
    void cleanUp() {
        System.clearProperty("baseUrl");
        System.clearProperty("viewport");
        PlaywrightSession.finish(false);
    }

    @Test
    void runConfigPrefersSystemPropertiesAndNormalises() {
        System.setProperty("baseUrl", "http://app:9000/");
        System.setProperty("viewport", "800x600");

        assertThat(RunConfig.baseUrl()).isEqualTo("http://app:9000");
        assertThat(RunConfig.viewport()).containsExactly(800, 600);
        assertThat(RunConfig.get("no.such.property", "NO_SUCH_ENV_VAR_REGGRAETON", "fallback")).isEqualTo("fallback");
    }

    @Test
    void pendingConversionAlwaysThrows() {
        assertThatThrownBy(() -> PendingConversion.fail("Steps.java:12 WebDriver.Options.logs"))
                .isInstanceOf(PendingConversionException.class)
                .hasMessageContaining("Not converted yet (reggraeton)")
                .hasMessageContaining("Steps.java:12");
    }

    @Test
    void literalRegexEscapesJavaScriptMetacharacters() {
        assertThat(Waits.literal("a.b(c)?")).isEqualTo("a\\.b\\(c\\)\\?");
        assertThat(java.util.regex.Pattern.compile(Waits.literal("/cart?id=[1]")).matcher("x/cart?id=[1]y").find()).isTrue();
    }

    @Test
    void dataTableOffersCucumberStyleViews() {
        DataTable table = DataTable.of(new String[][] {{"product", "quantity"}, {"Laptop", "1"}, {"Cable", "3"}});

        assertThat(table.asMaps()).containsExactly(
                java.util.Map.of("product", "Laptop", "quantity", "1"),
                java.util.Map.of("product", "Cable", "quantity", "3"));
        assertThat(table.asLists()).hasSize(3);
        assertThat(table.asList()).containsExactly("product", "quantity", "Laptop", "1", "Cable", "3");
        assertThat(table.column(0)).containsExactly("product", "Laptop", "Cable");
        assertThat(table.transpose().row(0)).containsExactly("product", "Laptop", "Cable");
        assertThat(table.height()).isEqualTo(3);
        assertThat(table.width()).isEqualTo(2);
    }

    static class Context {
    }

    static class Steps {
        final Context context;
        final Scenario scenario;

        Steps(Context context, Scenario scenario) {
            this.context = context;
            this.scenario = scenario;
        }
    }

    static class MoreSteps {
        final Context context;

        MoreSteps(Context context) {
            this.context = context;
        }
    }

    @Test
    void scenarioScopeSharesInstancesLikePicocontainer() {
        ScenarioScope scope = new ScenarioScope();
        Scenario scenario = new Scenario("a.feature:3", "s", List.of("@x"));
        scope.register(Scenario.class, scenario);

        Steps steps = scope.get(Steps.class);

        assertThat(steps.scenario).isSameAs(scenario);
        assertThat(scope.get(MoreSteps.class).context).isSameAs(steps.context);
        assertThat(scope.get(Steps.class)).isSameAs(steps);
        assertThat(scenario.getUri()).isEqualTo("a.feature");
        assertThat(scenario.getLine()).isEqualTo(3);
    }

    @Test
    void tagFilterUsesCucumberExpressions() {
        try {
            System.setProperty("cucumber.filter.tags", "@smoke and not @wip");
            assertThat(TagFilter.accepts(List.of("@smoke"))).isTrue();
            assertThat(TagFilter.accepts(List.of("@smoke", "@wip"))).isFalse();
            assertThat(TagFilter.accepts(List.of())).isFalse();
        } finally {
            System.clearProperty("cucumber.filter.tags");
        }
        assertThat(TagFilter.accepts(List.of())).isTrue();
    }

    @Test
    void lazyLocatorResolvesOnEveryCall() {
        AtomicInteger resolved = new AtomicInteger();
        Locator fake = (Locator) Proxy.newProxyInstance(Locator.class.getClassLoader(), new Class<?>[] {Locator.class},
                (proxy, method, args) -> method.getName().equals("innerText") ? "hello" : null);

        Locator lazy = Locators.lazy(() -> {
            resolved.incrementAndGet();
            return fake;
        });

        assertThat(resolved).hasValue(0);
        assertThat(lazy.innerText()).isEqualTo("hello");
        assertThat(lazy.innerText()).isEqualTo("hello");
        assertThat(resolved).hasValue(2);
        assertThat(lazy).isEqualTo(lazy).isNotEqualTo(fake);
    }

    @Test
    void sessionIsRequiredForCurrent() {
        assertThat(PlaywrightSession.isActive()).isFalse();
        assertThatThrownBy(PlaywrightSession::current).isInstanceOf(IllegalStateException.class);
        assertThat(PlaywrightSession.safeFileName("Login: rejected [3]")).isEqualTo("Login_rejected_3_");
    }

    // ---- browser tests: opt-in with -Dreggraeton.browserTests=true (uses an installed browser) ----

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void waitsAndLazyListsWorkInARealBrowser() {
        Page page = PlaywrightSession.start("runtime-test").page();
        page.setContent("""
                <h1 id="title">Shop</h1>
                <ul id="results"></ul>
                <input type="checkbox" id="agree">
                <script>
                  setTimeout(() => {
                    document.getElementById('results').innerHTML = '<li class="p">A</li><li class="p">B</li>';
                    document.getElementById('agree').checked = true;
                    document.title = 'Results';
                  }, 300);
                </script>
                """);
        Waits wait = new Waits(page, Duration.ofSeconds(5));

        assertThat(wait.visible("#results li.p").innerText()).isEqualTo("A");
        List<Locator> items = Locators.lazyAll(() -> page.locator("#results li.p"));
        assertThat(items).hasSize(2);
        assertThat(items.get(1).innerText()).isEqualTo("B");
        assertThat(wait.count("#results li.p", 2)).hasSize(2);
        assertThat(wait.selected("#agree")).isTrue();
        assertThat(wait.titleContains("Res")).isTrue();
        assertThat(wait.text("#title", "Sho")).isTrue();
        assertThat(PlaywrightSession.current().page()).isSameAs(page);
    }

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void selectQueriesUsedByTheTranslation() {
        Page page = PlaywrightSession.start("select-queries").page();
        page.setContent("<select id='c' multiple><option>Austria</option><option selected>Belgium</option>"
                + "<option selected>Chile</option></select><select id='s'><option>One</option></select>");
        Locator select = page.locator("#c");

        assertThat(Elements.findAll(select.locator("option"))).hasSize(3);
        assertThat(select.locator("option:checked").first().innerText()).isEqualTo("Belgium");
        assertThat(Elements.findAll(select.locator("option:checked"))).hasSize(2);
        assertThat(select.getAttribute("multiple") != null).isTrue();
        assertThat(page.locator("#s").getAttribute("multiple") != null).isFalse();
        select.selectOption(new String[0]);
        assertThat(Elements.findAll(select.locator("option:checked"))).isEmpty();
    }

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void dialogsAreAnsweredWhenTheyOpenAndCheckedAfterwards() {
        Page page = PlaywrightSession.start("dialogs").page();
        page.setContent("<button id='a' onclick=\"alert('Saved')\">a</button>"
                + "<button id='c' onclick=\"document.title = confirm('Delete?') ? 'yes' : 'no'\">c</button>"
                + "<button id='p' onclick=\"document.title = 'got:' + prompt('Name?')\">p</button>");

        // Selenium style: click, then switchTo().alert()
        page.locator("#a").click();
        Alert alert = Dialogs.alert(page);
        assertThat(alert.getText()).isEqualTo("Saved");
        alert.accept();
        assertThatThrownBy(() -> Dialogs.alert(page)).hasMessageContaining("No dialog was opened");

        // confirm accepted by default
        page.locator("#c").click();
        assertThat(page.title()).isEqualTo("yes");
        Dialogs.alert(page).accept();

        // dismiss registered before the click
        Dialogs.expectDismiss();
        page.locator("#c").click();
        assertThat(page.title()).isEqualTo("no");
        Dialogs.alert(page).dismiss();

        // dismiss() without registering first explains what to do
        page.locator("#c").click();
        assertThatThrownBy(() -> Dialogs.alert(page).dismiss())
                .hasMessageContaining("Dialogs.expectDismiss()").hasMessageContaining("Delete?");
        Dialogs.alert(page).accept();

        // prompt
        Dialogs.expectPrompt("Ada");
        page.locator("#p").click();
        assertThat(page.title()).isEqualTo("got:Ada");
        Alert prompt = new Waits(page).alert();
        prompt.sendKeys("Ada");
        prompt.accept();
    }

    @Test
    void scriptResultsAreNormalisedLikeSelenium() {
        assertThat(Scripts.normalise(3)).isEqualTo(3L);
        assertThat(Scripts.normalise(3.0)).isEqualTo(3L);
        assertThat(Scripts.normalise(2.5)).isEqualTo(2.5);
        assertThat(Scripts.normalise(java.util.List.of(1, 1.5, "x"))).isEqualTo(java.util.List.of(1L, 1.5, "x"));
    }

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void scriptsCookiesSelectsAndCustomWaitsInARealBrowser() {
        Page page = PlaywrightSession.start("scripts").page();
        page.navigate("data:text/html,<p id='p'>hello</p>"
                + "<select id='m' multiple><option selected>One</option><option selected> Two  words </option></select>"
                + "<select id='s'><option>Single</option></select>");

        // executeScript semantics: arguments[], return, elements, numbers as Long
        assertThat(Scripts.execute(page, "return arguments[0] + arguments[1];", 2, 3)).isEqualTo(5L);
        assertThat(Scripts.execute(page, "return arguments[0].textContent;", page.locator("#p"))).isEqualTo("hello");
        assertThat(Scripts.execute(page, "return arguments[0].length;", java.util.List.of(page.locator("#p"), "x"))).isEqualTo(2L);
        assertThat(Scripts.execute(page, "document.title = 'set';")).isNull();
        assertThat(page.title()).isEqualTo("set");
        assertThat(Scripts.executeAsync(page, "var cb = arguments[arguments.length - 1]; setTimeout(() => cb(arguments[0] * 2), 50);", 21))
                .isEqualTo(42L);

        // Select deselection, multi-select only
        Selects.deselectByVisibleText(page.locator("#m"), "Two words");
        assertThat(page.locator("#m option:checked").count()).isEqualTo(1);
        assertThatThrownBy(() -> Selects.deselectByIndex(page.locator("#s"), 0)).hasMessageContaining("multi-select");

        // custom wait condition
        page.evaluate("setTimeout(() => document.title = 'ready', 300)");
        String ready = new Waits(page, java.time.Duration.ofSeconds(5)).until(p -> p.title().equals("ready") ? p.title() : null);
        assertThat(ready).isEqualTo("ready");
        assertThatThrownBy(() -> new Waits(page, java.time.Duration.ofMillis(300)).until(p -> Boolean.FALSE))
                .isInstanceOf(com.microsoft.playwright.TimeoutError.class);
        PlaywrightSession.finish(false);
    }

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void cookiesOnAServedPage() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<p>shop</p>".getBytes();
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Page page = PlaywrightSession.start("cookies").page();
            page.navigate("http://127.0.0.1:" + server.getAddress().getPort() + "/");

            assertThat(Cookies.named(page, "session").getValue()).isEqualTo("abc");
            Cookies.add(page, new Cookie("theme", "dark"));
            assertThat(Cookies.all(page)).extracting(Cookie::getName).containsExactlyInAnyOrder("session", "theme");
            Cookies.delete(page, "session");
            assertThat(Cookies.named(page, "session")).isNull();
            Cookies.deleteAll(page);
            assertThat(Cookies.all(page)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "reggraeton.browserTests", matches = "true")
    void sendKeysTypesTextAndPressesKeys() {
        Page page = PlaywrightSession.start("send-keys").page();
        page.setContent("<input id='q' value='ab'><div id='log'></div>"
                + "<script>document.getElementById('q').addEventListener('keydown', e => {"
                + " if (e.key === 'Enter') document.getElementById('log').textContent = 'enter:' + e.target.value; });</script>");
        Locator input = page.locator("#q");

        Elements.sendKeys(input, "cd", Keys.ENTER);
        assertThat(page.locator("#log").innerText()).isEqualTo("enter:abcd");

        Elements.sendKeys(input, Keys.chord(Keys.CONTROL, "a"), "z");
        assertThat(input.inputValue()).isEqualTo("z");
        assertThat(Keys.of('\uE007') == Keys.ENTER).isTrue();
        assertThat("x" + Keys.TAB).hasSize(2);
    }
}

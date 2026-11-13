package io.reggraeton.scan.java;

import io.reggraeton.Samples;
import io.reggraeton.TempRepo;
import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.scan.Assessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphBuilderTest {

    private static CallGraph graph(Path root) {
        JavaSourceIndex index = JavaSourceIndex.build(root, List.of());
        return new CallGraphBuilder(index, new GlueScanner(index).scan()).build();
    }

    private static CallGraph.Node node(CallGraph graph, String id) {
        return graph.nodes().stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id));
    }

    @Test
    void followsStepsIntoPageObjectsAndSelenium() {
        CallGraph graph = graph(Samples.legacyShop());
        Assessor assessor = new Assessor(graph);

        Set<String> reach = assessor.reachable("com.example.shop.steps.LoginSteps#iLogInAs(String,String)");
        assertThat(reach).contains(
                "com.example.shop.pages.LoginPage#loginAs(String,String)",
                "com.example.shop.pages.BasePage#type(WebElement,String)",
                "com.example.shop.support.TestContext#setCurrentUser(String)");

        // lambda step -> page object constructor -> base constructor + class-level @FindBy fields
        Set<String> lambda = assessor.reachable(graph.nodes().stream()
                .filter(n -> n.kind().equals("step") && n.id().startsWith("com.example.shop.steps.CartSteps#Given@L"))
                .findFirst().orElseThrow().id());
        assertThat(lambda).contains(
                "com.example.shop.pages.ProductsPage#<init>(WebDriver)",
                "com.example.shop.pages.BasePage#<init>(WebDriver)",
                "com.example.shop.pages.ProductsPage",
                "com.example.shop.pages.CartPage#<init>(WebDriver)");

        assertThat(node(graph, "com.example.shop.pages.BasePage#type(WebElement,String)").apiUsages())
                .extracting(ApiUsage::api).containsExactlyInAnyOrder("WebElement.clear", "WebElement.sendKeys");
        assertThat(node(graph, "com.example.shop.pages.ProductsPage#sortBy(String)").apiUsages())
                .extracting(ApiUsage::api).contains("Select.<init>", "Select.selectByVisibleText", "By.id");
        assertThat(node(graph, "com.example.shop.pages.LoginPage").apiUsages())
                .extracting(ApiUsage::api).containsOnly("@FindBy").hasSize(3);
        assertThat(node(graph, "com.example.shop.support.DriverFactory#create()").apiUsages())
                .filteredOn(u -> u.category().equals(ApiUsage.DRIVER_SETUP))
                .extracting(ApiUsage::api).contains("ChromeDriver.<init>", "ChromeOptions.<init>", "WebDriverManager.chromedriver");

        assertThat(node(graph, "com.example.shop.steps.SearchSteps#noErrorsAreLoggedInTheBrowserConsole()").apiUsages())
                .filteredOn(u -> u.support().equals(ApiUsage.MANUAL))
                .extracting(ApiUsage::api).contains("WebDriver.Options.logs", "Logs.get", "LogEntries.getAll");
        assertThat(node(graph, "com.example.shop.steps.LoginSteps#iShouldSeeTheError(String)").apiUsages())
                .extracting(ApiUsage::category).containsOnly(ApiUsage.ASSERTION);

        assertThat(graph.edges()).allMatch(CallGraph.Edge::resolved);
        assertThat(graph.edges()).extracting(e -> e.from() + "->" + e.to()).doesNotHaveDuplicates();
    }

    @Test
    void reportsExternalLibrariesAndArgumentSensitiveRules(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Steps", """
                import com.acme.qa.Evidence;
                import io.cucumber.java.en.When;
                import org.openqa.selenium.By;
                import org.openqa.selenium.JavascriptExecutor;
                import org.openqa.selenium.WebDriver;
                import org.openqa.selenium.WebElement;
                import org.openqa.selenium.support.ui.WebDriverWait;
                import java.time.Duration;

                public class Steps {
                    private WebDriver driver;

                    @When("something happens")
                    public void something() {
                        WebElement button = driver.findElement(By.id("go"));
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", button);
                        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
                        new WebDriverWait(driver, Duration.ofSeconds(3)).until(d -> d.getTitle().startsWith("x"));
                        Evidence.attach(driver, "something");
                        helper();
                    }

                    private void helper() {
                        driver.switchTo().frame("payment");
                    }
                }
                """);

        CallGraph graph = graph(dir);
        CallGraph.Node step = node(graph, "x.Steps#something()");

        assertThat(step.apiUsages()).filteredOn(u -> u.api().equals("JavascriptExecutor.executeScript"))
                .extracting(ApiUsage::support).containsExactly(ApiUsage.AUTO, ApiUsage.AUTO); // Scripts.execute handles arguments
        assertThat(step.apiUsages()).filteredOn(u -> u.api().equals("FluentWait.until"))
                .singleElement().satisfies(u -> assertThat(u.support()).isEqualTo(ApiUsage.AUTO)); // Waits.until(lambda)
        assertThat(step.apiUsages()).filteredOn(u -> u.category().equals(ApiUsage.EXTERNAL))
                .singleElement().satisfies(u -> {
                    assertThat(u.api()).isEqualTo("Evidence.attach");
                    assertThat(u.support()).isEqualTo(ApiUsage.MANUAL);
                });
        assertThat(graph.edges()).anyMatch(e -> e.from().equals("x.Steps#something()") && e.to().equals("x.Steps#helper()"));
        assertThat(node(graph, "x.Steps#helper()").apiUsages())
                .extracting(ApiUsage::api, ApiUsage::support)
                .contains(org.assertj.core.groups.Tuple.tuple("WebDriver.TargetLocator.frame", ApiUsage.MANUAL));
    }

    @Test
    void catalogueNamesAreWellFormed() {
        assertThat(SeleniumApiCatalog.entries().keySet())
                .allMatch(api -> api.startsWith("@") || api.matches("[A-Z][A-Za-z.]*\\.(<init>|[a-z][A-Za-z]*)"));
        assertThat(SeleniumApiCatalog.normalise("ChromeDriver.get")).isEqualTo("WebDriver.get");
        assertThat(SeleniumApiCatalog.normalise("WebDriverWait.until")).isEqualTo("FluentWait.until");
        assertThat(JavaSourceIndex.withoutPackage("org.openqa.selenium.WebDriver.Options")).isEqualTo("WebDriver.Options");
    }
}

package com.example.shop.steps;

import com.example.shop.pages.ProductsPage;
import com.example.shop.support.TestContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-style regular-expression step definitions. */
public class SearchSteps {

    private final TestContext context;

    public SearchSteps(TestContext context) {
        this.context = context;
    }

    private ProductsPage products() {
        return new ProductsPage(context.getDriver());
    }

    @When("^I search for \"([^\"]*)\"$")
    public void iSearchFor(String term) {
        products().search(term);
    }

    @When("^I sort the results by \"(.*)\"$")
    public void iSortTheResultsBy(String option) {
        products().sortBy(option);
    }

    @Then("^the first result is \"(.*)\"$")
    public void theFirstResultIs(String expected) {
        assertEquals(expected, products().resultNames().get(0));
    }

    @When("I type {string} into the search box")
    public void iTypeIntoTheSearchBox(String text) {
        products().typeSlowly(text);
    }

    @Then("no errors are logged in the browser console")
    public void noErrorsAreLoggedInTheBrowserConsole() {
        LogEntries logs = context.getDriver().manage().logs().get(LogType.BROWSER);
        assertTrue(logs.getAll().isEmpty(), "Browser console contains: " + logs.getAll());
    }
}

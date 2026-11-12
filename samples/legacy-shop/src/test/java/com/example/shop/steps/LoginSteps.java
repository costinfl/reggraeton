package com.example.shop.steps;

import com.example.shop.pages.LoginPage;
import com.example.shop.pages.ProductsPage;
import com.example.shop.support.TestContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LoginSteps {

    private final TestContext context;
    private LoginPage loginPage;

    public LoginSteps(TestContext context) {
        this.context = context;
    }

    @Given("I am on the login page")
    public void iAmOnTheLoginPage() {
        loginPage = new LoginPage(context.getDriver()).openPage();
    }

    @When("I log in as {string} with password {string}")
    public void iLogInAs(String user, String password) {
        loginPage.loginAs(user, password);
        context.setCurrentUser(user);
    }

    @Given("I am logged in as {string}")
    public void iAmLoggedInAs(String user) {
        iAmOnTheLoginPage();
        iLogInAs(user, "secret123");
        new ProductsPage(context.getDriver()).welcomeText();
    }

    @Then("I should see the welcome message {string}")
    public void iShouldSeeTheWelcomeMessage(String expected) {
        assertEquals(expected, new ProductsPage(context.getDriver()).welcomeText());
    }

    @Then("I should see the error {string}")
    public void iShouldSeeTheError(String expected) {
        assertEquals(expected, loginPage.errorMessage());
    }

    /** Not used by any feature file any more. */
    @When("I log out")
    public void iLogOut() {
        context.getDriver().manage().deleteAllCookies();
    }
}

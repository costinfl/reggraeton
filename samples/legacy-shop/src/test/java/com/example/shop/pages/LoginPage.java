package com.example.shop.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.How;

public class LoginPage extends BasePage {

    @FindBy(id = "username")
    private WebElement usernameField;

    @FindBy(how = How.ID, using = "password")
    private WebElement passwordField;

    @FindBy(css = "button[type='submit']")
    private WebElement loginButton;

    public LoginPage(WebDriver driver) {
        super(driver);
    }

    public LoginPage openPage() {
        open("/login.html");
        return this;
    }

    public void loginAs(String user, String password) {
        type(usernameField, user);
        type(passwordField, password);
        loginButton.click();
    }

    public String errorMessage() {
        return waitForVisible(By.className("error-message")).getText();
    }
}

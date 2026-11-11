package com.example.shop.support;

import org.openqa.selenium.WebDriver;

/** Shared per-scenario state, injected by cucumber-picocontainer. */
public class TestContext {

    private WebDriver driver;
    private String currentUser;

    public WebDriver getDriver() {
        return driver;
    }

    public void setDriver(WebDriver driver) {
        this.driver = driver;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(String currentUser) {
        this.currentUser = currentUser;
    }
}

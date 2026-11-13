package com.example.shop.hooks;

import com.example.shop.support.DriverFactory;
import com.example.shop.support.TestContext;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

public class Hooks {

    private final TestContext context;

    public Hooks(TestContext context) {
        this.context = context;
    }

    @Before(order = 0)
    public void startBrowser() {
        context.setDriver(DriverFactory.create());
    }

    @Before(value = "@cart", order = 10)
    public void clearCart() {
        context.getDriver().get(com.example.shop.support.Config.baseUrl() + "/login.html");
        ((JavascriptExecutor) context.getDriver()).executeScript("window.localStorage.clear();");
        com.example.shop.support.Database.execute("DELETE FROM cart_items WHERE user_name = '" + context.getCurrentUser() + "'");
        com.example.shop.support.Database.nextOrderId();
    }

    @After
    public void stopBrowser(Scenario scenario) {
        if (scenario.isFailed()) {
            byte[] png = ((TakesScreenshot) context.getDriver()).getScreenshotAs(OutputType.BYTES);
            scenario.attach(png, "image/png", scenario.getName());
        }
        context.getDriver().quit();
    }
}

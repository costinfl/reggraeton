package com.example.shop.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;

import java.util.List;
import java.util.stream.Collectors;

public class ProductsPage extends BasePage {

    @FindBy(name = "q")
    private WebElement searchBox;

    @FindBy(id = "search-btn")
    private WebElement searchButton;

    @FindBy(css = "#results li.product")
    private List<WebElement> results;

    public ProductsPage(WebDriver driver) {
        super(driver);
    }

    public String welcomeText() {
        return waitForVisible(By.id("welcome")).getText();
    }

    public void search(String term) {
        type(searchBox, term);
        searchButton.click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#results li.product")));
    }

    public void typeSlowly(String text) {
        new Actions(driver).click(searchBox).sendKeys(text).perform();
    }

    public void pressEnter() {
        searchBox.sendKeys(Keys.ENTER);
    }

    public void sortBy(String visibleText) {
        new Select(driver.findElement(By.id("sort"))).selectByVisibleText(visibleText);
    }

    public List<String> resultNames() {
        return results.stream().map(e -> e.findElement(By.cssSelector(".name")).getText()).collect(Collectors.toList());
    }

    public void addToCart(String productName) {
        driver.findElement(By.xpath("//li[@data-name='" + productName + "']//button[contains(@class,'add-to-cart')]")).click();
    }

    public String cartBadge() {
        return driver.findElement(By.cssSelector("nav.cart .badge")).getText();
    }

    public CartPage openCart() {
        driver.findElement(By.linkText("Cart")).click();
        return new CartPage(driver);
    }
}

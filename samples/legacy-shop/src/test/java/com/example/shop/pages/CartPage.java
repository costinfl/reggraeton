package com.example.shop.pages;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

import java.math.BigDecimal;
import java.util.List;

public class CartPage extends BasePage {

    @FindBy(css = "#cart-items tr.item")
    private List<WebElement> lines;

    @FindBy(id = "notes")
    private WebElement notes;

    public CartPage(WebDriver driver) {
        super(driver);
    }

    public int lineCount() {
        return lines.size();
    }

    public BigDecimal total() {
        return new BigDecimal(driver.findElement(By.id("total")).getText().trim());
    }

    public void checkoutWithNote(String note) {
        type(notes, note);
        driver.findElement(By.id("checkout")).click();
    }

    public void emptyCart() {
        driver.findElement(By.id("empty-cart")).click();
        driver.switchTo().alert().accept();
    }

    /** Clicks "Empty cart", cancels the confirmation and returns its question. */
    public String cancelEmptyCart() {
        driver.findElement(By.id("empty-cart")).click();
        Alert alert = driver.switchTo().alert();
        String question = alert.getText();
        alert.dismiss();
        return question;
    }

    public boolean confirmationShown() {
        return waitForVisible(By.id("confirmation")).isDisplayed();
    }
}

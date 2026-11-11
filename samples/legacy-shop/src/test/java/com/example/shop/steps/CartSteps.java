package com.example.shop.steps;

import com.example.shop.pages.CartPage;
import com.example.shop.pages.ProductsPage;
import com.example.shop.support.TestContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java8.En;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lambda-style (cucumber-java8) step definitions. */
public class CartSteps implements En {

    private CartPage cartPage;

    public CartSteps(TestContext context) {

        When("I add {string} to the cart", (String product) -> {
            new ProductsPage(context.getDriver()).addToCart(product);
        });

        Then("the cart badge shows {int} item(s)", (Integer count) -> {
            assertEquals(String.valueOf(count), new ProductsPage(context.getDriver()).cartBadge());
        });

        When("I add the following products to the cart:", (DataTable table) -> {
            ProductsPage products = new ProductsPage(context.getDriver());
            for (Map<String, String> row : table.asMaps()) {
                products.search(row.get("product"));
                int quantity = Integer.parseInt(row.get("quantity"));
                for (int i = 0; i < quantity; i++) {
                    products.addToCart(row.get("product"));
                }
            }
            cartPage = products.openCart();
        });

        Then("the cart contains {int} lines", (Integer lines) -> {
            assertEquals(lines.intValue(), cartPage.lineCount());
        });

        Then("the cart total is {amount} EUR", (BigDecimal amount) -> {
            assertEquals(0, amount.compareTo(cartPage.total()));
        });

        Given("the cart contains {string}", (String product) -> {
            ProductsPage products = new ProductsPage(context.getDriver());
            products.search(product);
            products.addToCart(product);
            cartPage = products.openCart();
        });

        When("I check out with the note:", (String note) -> {
            cartPage.checkoutWithNote(note);
        });

        Then("I see the order confirmation", () -> {
            assertTrue(cartPage.confirmationShown());
        });
    }
}

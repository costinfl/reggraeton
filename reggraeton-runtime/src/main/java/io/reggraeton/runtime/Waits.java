package io.reggraeton.runtime;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PageAssertions;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Replacement for Selenium's {@code WebDriverWait} + {@code ExpectedConditions}: every condition
 * is a Playwright web-first wait or assertion, honouring the timeout given to the constructor.
 * Selector arguments are Playwright selectors (what {@code By} locators were converted into).
 */
public final class Waits {

    private final Page page;
    private double timeoutMs;

    public Waits(Page page) {
        this(page, Double.NaN);
    }

    public Waits(Page page, Duration timeout) {
        this(page, (double) timeout.toMillis());
    }

    /** Selenium 3 style {@code new WebDriverWait(driver, seconds)}. */
    public Waits(Page page, long timeoutSeconds) {
        this(page, timeoutSeconds * 1000.0);
    }

    private Waits(Page page, double timeoutMs) {
        this.page = page;
        this.timeoutMs = timeoutMs;
    }

    // FluentWait configuration -----------------------------------------------------------------

    public Waits withTimeout(Duration timeout) {
        this.timeoutMs = timeout.toMillis();
        return this;
    }

    /** Selenium 3 style {@code withTimeout(10, TimeUnit.SECONDS)}. */
    public Waits withTimeout(long amount, java.util.concurrent.TimeUnit unit) {
        this.timeoutMs = unit.toMillis(amount);
        return this;
    }

    /** Polling is handled by Playwright. */
    public Waits pollingEvery(Duration interval) {
        return this;
    }

    /** Polling is handled by Playwright. */
    public Waits pollingEvery(long amount, java.util.concurrent.TimeUnit unit) {
        return this;
    }

    /** Playwright retries until the timeout regardless of intermediate errors. */
    public Waits ignoring(Class<?>... exceptionTypes) {
        return this;
    }

    public Waits withMessage(String message) {
        return this;
    }

    // Element conditions ------------------------------------------------------------------------

    /** {@code visibilityOfElementLocated} / {@code visibilityOf}: first match becomes visible. */
    public Locator visible(String selector) {
        return visible(page.locator(selector));
    }

    public Locator visible(Locator locator) {
        Locator first = locator.first();
        first.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeout()));
        return first;
    }

    /** {@code elementToBeClickable}: visible and enabled. */
    public Locator clickable(String selector) {
        return clickable(page.locator(selector));
    }

    public Locator clickable(Locator locator) {
        Locator first = visible(locator);
        assertThat(first).isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(timeout()));
        return first;
    }

    /** {@code presenceOfElementLocated}: first match is attached to the DOM. */
    public Locator present(String selector) {
        return present(page.locator(selector));
    }

    public Locator present(Locator locator) {
        Locator first = locator.first();
        first.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(timeout()));
        return first;
    }

    /** {@code invisibilityOfElementLocated} / {@code invisibilityOf}: hidden or detached. */
    public boolean hidden(String selector) {
        return hidden(page.locator(selector));
    }

    public boolean hidden(Locator locator) {
        locator.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(timeout()));
        return true;
    }

    /** {@code textToBePresentInElement(Located)}: the element's text contains {@code text}. */
    public boolean text(String selector, String text) {
        return text(page.locator(selector), text);
    }

    public boolean text(Locator locator, String text) {
        assertThat(locator.first()).containsText(text, new LocatorAssertions.ContainsTextOptions().setTimeout(timeout()));
        return true;
    }

    /** {@code textToBe}: the element's text equals {@code text}. */
    public boolean exactText(String selector, String text) {
        return exactText(page.locator(selector), text);
    }

    public boolean exactText(Locator locator, String text) {
        assertThat(locator.first()).hasText(text, new LocatorAssertions.HasTextOptions().setTimeout(timeout()));
        return true;
    }

    /** {@code numberOfElementsToBe}. */
    public List<Locator> count(String selector, int count) {
        return count(page.locator(selector), count);
    }

    public List<Locator> count(Locator locator, int count) {
        assertThat(locator).hasCount(count, new LocatorAssertions.HasCountOptions().setTimeout(timeout()));
        return locator.all();
    }

    /** {@code elementToBeSelected}. */
    public boolean selected(String selector) {
        return selected(page.locator(selector));
    }

    public boolean selected(Locator locator) {
        assertThat(locator.first()).isChecked(new LocatorAssertions.IsCheckedOptions().setTimeout(timeout()));
        return true;
    }

    /** {@code attributeToBe}. */
    public boolean attribute(String selector, String name, String value) {
        return attribute(page.locator(selector), name, value);
    }

    public boolean attribute(Locator locator, String name, String value) {
        assertThat(locator.first()).hasAttribute(name, value, new LocatorAssertions.HasAttributeOptions().setTimeout(timeout()));
        return true;
    }

    /** {@code visibilityOfAllElementsLocatedBy}: at least one match, all visible. */
    public List<Locator> allVisible(String selector) {
        return allVisible(page.locator(selector));
    }

    public List<Locator> allVisible(Locator locator) {
        visible(locator);
        List<Locator> all = locator.all();
        for (Locator each : all) {
            each.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeout()));
        }
        return all;
    }

    /** {@code presenceOfAllElementsLocatedBy}: at least one match attached. */
    public List<Locator> allPresent(String selector) {
        return allPresent(page.locator(selector));
    }

    public List<Locator> allPresent(Locator locator) {
        present(locator);
        return locator.all();
    }

    // Page conditions ---------------------------------------------------------------------------

    public boolean titleIs(String title) {
        assertThat(page).hasTitle(title, new PageAssertions.HasTitleOptions().setTimeout(timeout()));
        return true;
    }

    public boolean titleContains(String fragment) {
        assertThat(page).hasTitle(Pattern.compile(literal(fragment)),
                new PageAssertions.HasTitleOptions().setTimeout(timeout()));
        return true;
    }

    public boolean urlIs(String url) {
        assertThat(page).hasURL(url, new PageAssertions.HasURLOptions().setTimeout(timeout()));
        return true;
    }

    public boolean urlContains(String fragment) {
        assertThat(page).hasURL(Pattern.compile(literal(fragment)),
                new PageAssertions.HasURLOptions().setTimeout(timeout()));
        return true;
    }

    public boolean urlMatches(String regex) {
        assertThat(page).hasURL(Pattern.compile(regex), new PageAssertions.HasURLOptions().setTimeout(timeout()));
        return true;
    }

    /**
     * Regex matching {@code text} literally. Not {@link Pattern#quote}: Playwright evaluates patterns in
     * the browser's JavaScript regex engine, which does not understand {@code \\Q...\\E}.
     */
    static String literal(String text) {
        return text.replaceAll("[\\\\^$.|?*+()\\[\\]{}/-]", "\\\\$0");
    }

    private double timeout() {
        return Double.isNaN(timeoutMs) ? RunConfig.timeoutMs() : timeoutMs;
    }
}

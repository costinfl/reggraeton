package io.reggraeton.runtime;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Element helpers whose Selenium semantics have no one-call Playwright equivalent. */
public final class Elements {

    private Elements() {
    }

    /**
     * Selenium {@code WebElement.sendKeys(...)}: types text (appending, like Selenium), presses
     * {@link Keys} and handles chords: a modifier stays down until {@link Keys#NULL} or the end.
     */
    public static void sendKeys(Locator element, CharSequence... parts) {
        StringBuilder all = new StringBuilder();
        for (CharSequence part : parts) {
            all.append(part);
        }
        element.focus();
        // WebDriver puts the caret at the end of the existing value before typing
        element.evaluate("e => { try { if (typeof e.value === 'string' && e.setSelectionRange) "
                + "e.setSelectionRange(e.value.length, e.value.length); } catch (ignored) {} }");
        Keyboard keyboard = element.page().keyboard();
        Deque<String> held = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            Keys key = Keys.of(c);
            if (key == null) {
                text.append(c);
                continue;
            }
            flush(keyboard, text);
            if (key == Keys.NULL) {
                while (!held.isEmpty()) {
                    keyboard.up(held.pop());
                }
            } else if (key.isModifier()) {
                keyboard.down(key.playwrightKey());
                held.push(key.playwrightKey());
            } else {
                keyboard.press(key.playwrightKey());
            }
        }
        flush(keyboard, text);
        while (!held.isEmpty()) {
            keyboard.up(held.pop());
        }
    }

    /**
     * Selenium {@code findElements}: waits for the current page to finish loading (Selenium does after a
     * click that navigates), then for at most {@link RunConfig#implicitWaitMs()} for a first match, and
     * returns all matches (possibly none).
     */
    public static List<Locator> findAll(Locator locator) {
        settle(locator);
        return locator.all();
    }

    /** Number of matches, with the same waiting as {@link #findAll}. */
    static int count(Locator locator) {
        settle(locator);
        return locator.count();
    }

    private static void settle(Locator locator) {
        try {
            locator.page().waitForLoadState();
        } catch (PlaywrightException e) {
            // page closed or navigating away: let the lookup itself report problems
        }
        double implicitWait = RunConfig.implicitWaitMs();
        if (implicitWait > 0) {
            try {
                locator.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(implicitWait));
            } catch (PlaywrightException e) {
                // no match within the implicit wait: an empty list, like Selenium
            }
        }
    }

    private static void flush(Keyboard keyboard, StringBuilder text) {
        if (text.length() > 0) {
            keyboard.type(text.toString());
            text.setLength(0);
        }
    }
}

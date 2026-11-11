package io.reggraeton.runtime;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;

import java.util.ArrayDeque;
import java.util.Deque;

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

    private static void flush(Keyboard keyboard, StringBuilder text) {
        if (text.length() > 0) {
            keyboard.type(text.toString());
            text.setLength(0);
        }
    }
}

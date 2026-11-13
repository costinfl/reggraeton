package io.reggraeton.runtime;

import com.microsoft.playwright.Locator;

/**
 * Selenium {@code Select} deselection on Playwright, which has no deselect API. Like Selenium,
 * deselecting only works on {@code <select multiple>}.
 */
public final class Selects {

    private static final String DESELECT = """
            (el, args) => {
              if (!el.multiple) {
                throw new Error('You may only deselect options of a multi-select');
              }
              const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim();
              const [by, wanted] = args;
              Array.from(el.options).forEach((o, i) => {
                const hit = by === 'text' ? norm(o.text) === norm(wanted)
                          : by === 'value' ? o.value === wanted
                          : i === Number(wanted);
                if (hit) { o.selected = false; }
              });
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
            }""";

    private Selects() {
    }

    public static void deselectByVisibleText(Locator select, String text) {
        select.evaluate(DESELECT, java.util.List.of("text", text));
    }

    public static void deselectByValue(Locator select, String value) {
        select.evaluate(DESELECT, java.util.List.of("value", value));
    }

    public static void deselectByIndex(Locator select, int index) {
        select.evaluate(DESELECT, java.util.List.of("index", String.valueOf(index)));
    }
}

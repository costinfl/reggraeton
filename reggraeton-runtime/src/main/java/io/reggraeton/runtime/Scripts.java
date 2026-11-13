package io.reggraeton.runtime;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selenium {@code JavascriptExecutor} semantics on Playwright: the script is a function body that
 * reads {@code arguments[i]} and may {@code return} a value; element arguments are passed as DOM
 * elements; integral numbers come back as {@code Long} and other numbers as {@code Double}, as in
 * Selenium, so existing casts keep working.
 */
public final class Scripts {

    private Scripts() {
    }

    /** Selenium {@code executeScript(script, args...)}. */
    public static Object execute(Page page, String script, Object... args) {
        String function = "(args) => (function() {\n" + script + "\n}).apply(null, args)";
        return normalise(page.evaluate(function, convert(args)));
    }

    /**
     * Selenium {@code executeAsyncScript(script, args...)}: the script receives a callback as its last
     * argument and calls it with the result.
     */
    public static Object executeAsync(Page page, String script, Object... args) {
        String function = "(args) => new Promise((resolve) => { (function() {\n" + script
                + "\n}).apply(null, args.concat([resolve])); })";
        return normalise(page.evaluate(function, convert(args)));
    }

    private static List<Object> convert(Object[] args) {
        List<Object> converted = new ArrayList<>();
        if (args != null) {
            for (Object arg : args) {
                converted.add(convertOne(arg));
            }
        }
        return converted;
    }

    private static Object convertOne(Object arg) {
        if (arg instanceof Locator locator) {
            ElementHandle handle = locator.elementHandle();
            return handle;
        }
        if (arg instanceof Object[] array) {
            return convert(array);
        }
        if (arg instanceof Collection<?> collection) {
            return convert(collection.toArray());
        }
        if (arg instanceof Map<?, ?> map) {
            Map<Object, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(k, convertOne(v)));
            return converted;
        }
        return arg;
    }

    /** Selenium returns Long for integral numbers and Double otherwise, recursively. */
    static Object normalise(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
            return d.longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(normalise(v)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, normalise(v)));
            return out;
        }
        if (value instanceof Object[] array) {
            return normalise(Arrays.asList(array));
        }
        return value;
    }
}

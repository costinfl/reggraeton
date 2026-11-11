package io.reggraeton.runtime;

import com.microsoft.playwright.Locator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Lazily created locators: the replacement for Selenium's {@code @FindBy} fields, which
 * {@code PageFactory} also resolved lazily. The supplier runs on every use, so a field can be
 * declared before the page it belongs to is available.
 */
public final class Locators {

    private Locators() {
    }

    /** A {@link Locator} that delegates every call to {@code supplier.get()}. */
    public static Locator lazy(Supplier<Locator> supplier) {
        return (Locator) Proxy.newProxyInstance(Locator.class.getClassLoader(), new Class<?>[] {Locator.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals":
                            if (method.getParameterCount() == 1) {
                                return proxy == args[0];
                            }
                            break;
                        case "hashCode":
                            if (method.getParameterCount() == 0) {
                                return System.identityHashCode(proxy);
                            }
                            break;
                        case "toString":
                            if (method.getParameterCount() == 0) {
                                return "Lazy(" + supplier.get() + ")";
                            }
                            break;
                        default:
                            break;
                    }
                    try {
                        return method.invoke(supplier.get(), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * A live list of all elements matching {@code supplier.get()}, like a Selenium
     * {@code @FindBy List<WebElement>}: size and items are looked up on each access.
     */
    public static List<Locator> lazyAll(Supplier<Locator> supplier) {
        return new AbstractList<>() {
            @Override
            public Locator get(int index) {
                if (index < 0 || index >= size()) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for " + size() + " matches of " + supplier.get());
                }
                return supplier.get().nth(index);
            }

            @Override
            public int size() {
                return supplier.get().count();
            }
        };
    }
}

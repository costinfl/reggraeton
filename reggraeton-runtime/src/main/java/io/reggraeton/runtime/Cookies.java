package io.reggraeton.runtime;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SameSiteAttribute;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selenium {@code driver.manage()} cookie methods on Playwright. Like Selenium, they work on the
 * cookies visible to the page's current URL.
 */
public final class Cookies {

    private Cookies() {
    }

    /** {@code manage().getCookies()} */
    public static Set<Cookie> all(Page page) {
        Set<Cookie> cookies = new LinkedHashSet<>();
        for (com.microsoft.playwright.options.Cookie c : page.context().cookies(page.url())) {
            cookies.add(toSelenium(c));
        }
        return cookies;
    }

    /** {@code manage().getCookieNamed(name)}: null when there is none. */
    public static Cookie named(Page page, String name) {
        for (Cookie cookie : all(page)) {
            if (cookie.getName().equals(name)) {
                return cookie;
            }
        }
        return null;
    }

    /** {@code manage().addCookie(cookie)}: without a domain, the cookie is set for the current page's host. */
    public static void add(Page page, Cookie cookie) {
        com.microsoft.playwright.options.Cookie c = new com.microsoft.playwright.options.Cookie(cookie.getName(), cookie.getValue());
        if (cookie.getDomain() != null) {
            c.setDomain(cookie.getDomain()).setPath(cookie.getPath());
        } else {
            String url = page.url();
            String origin = url.replaceFirst("^(\\w+://[^/]+).*$", "$1");
            c.setUrl(origin + cookie.getPath());
        }
        if (cookie.getExpiry() != null) {
            c.setExpires(cookie.getExpiry().getTime() / 1000.0);
        }
        c.setSecure(cookie.isSecure()).setHttpOnly(cookie.isHttpOnly());
        if (cookie.getSameSite() != null) {
            c.setSameSite(SameSiteAttribute.valueOf(cookie.getSameSite().toUpperCase(Locale.ROOT)));
        }
        page.context().addCookies(List.of(c));
    }

    /** {@code manage().deleteCookieNamed(name)} */
    public static void delete(Page page, String name) {
        page.context().clearCookies(new BrowserContext.ClearCookiesOptions().setName(name));
    }

    /** {@code manage().deleteCookie(cookie)} */
    public static void delete(Page page, Cookie cookie) {
        delete(page, cookie.getName());
    }

    /** {@code manage().deleteAllCookies()} */
    public static void deleteAll(Page page) {
        page.context().clearCookies();
    }

    private static Cookie toSelenium(com.microsoft.playwright.options.Cookie c) {
        Date expiry = c.expires == null || c.expires < 0 ? null : new Date((long) (c.expires * 1000));
        return new Cookie(c.name, c.value, c.domain, c.path, expiry, Boolean.TRUE.equals(c.secure),
                Boolean.TRUE.equals(c.httpOnly), c.sameSite == null ? null : c.sameSite.name());
    }
}

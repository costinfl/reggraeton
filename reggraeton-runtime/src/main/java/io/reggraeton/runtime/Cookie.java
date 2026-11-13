package io.reggraeton.runtime;

import java.util.Date;
import java.util.Objects;

/**
 * Stand-in for Selenium's {@code org.openqa.selenium.Cookie}: same constructors and getters, used
 * with {@link Cookies}.
 */
public final class Cookie {

    private final String name;
    private final String value;
    private final String domain;
    private final String path;
    private final Date expiry;
    private final boolean secure;
    private final boolean httpOnly;
    private final String sameSite;

    public Cookie(String name, String value) {
        this(name, value, null, "/", null, false, false, null);
    }

    public Cookie(String name, String value, String path) {
        this(name, value, null, path, null, false, false, null);
    }

    public Cookie(String name, String value, String path, Date expiry) {
        this(name, value, null, path, expiry, false, false, null);
    }

    public Cookie(String name, String value, String domain, String path, Date expiry) {
        this(name, value, domain, path, expiry, false, false, null);
    }

    public Cookie(String name, String value, String domain, String path, Date expiry, boolean isSecure) {
        this(name, value, domain, path, expiry, isSecure, false, null);
    }

    public Cookie(String name, String value, String domain, String path, Date expiry, boolean isSecure, boolean isHttpOnly) {
        this(name, value, domain, path, expiry, isSecure, isHttpOnly, null);
    }

    public Cookie(String name, String value, String domain, String path, Date expiry, boolean isSecure,
                  boolean isHttpOnly, String sameSite) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path == null || path.isEmpty() ? "/" : path;
        this.expiry = expiry == null ? null : new Date(expiry.getTime() / 1000 * 1000);
        this.secure = isSecure;
        this.httpOnly = isHttpOnly;
        this.sameSite = sameSite;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getDomain() {
        return domain;
    }

    public String getPath() {
        return path;
    }

    public Date getExpiry() {
        return expiry == null ? null : new Date(expiry.getTime());
    }

    public boolean isSecure() {
        return secure;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public String getSameSite() {
        return sameSite;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cookie c && Objects.equals(name, c.name) && Objects.equals(value, c.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public String toString() {
        return name + "=" + value + (domain != null ? "; domain=" + domain : "") + "; path=" + path
                + (expiry != null ? "; expires=" + expiry : "") + (secure ? "; secure" : "") + (httpOnly ? "; httpOnly" : "");
    }
}

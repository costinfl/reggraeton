package io.reggraeton.scan.java;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What to do with calls into libraries outside the scanned repository. Read from
 * {@code reggraeton.properties} (in the scanned repository's root, or given with {@code --config}):
 *
 * <pre>
 * # calls kept unchanged (the generated pom keeps the original project's dependencies)
 * external.keep   = com.acme.logging, org.apache.commons.lang3.StringUtils
 * # calls removed from the generated code (only when they are a statement of their own)
 * external.drop   = com.acme.qa.Evidence.attach, com.acme.metrics
 * # always left for manual conversion
 * external.manual = com.acme.qa.legacy
 * </pre>
 *
 * Entries are prefixes of {@code package.Class.method}. Unlisted calls are kept when no Selenium
 * object is passed to or returned from them, and are manual otherwise.
 */
public final class ExternalPolicy {

    /** Decision for one external call. */
    public enum Decision { KEEP, DROP, MANUAL }

    public static final String FILE_NAME = "reggraeton.properties";

    private final List<String> keep;
    private final List<String> drop;
    private final List<String> manual;
    private final String source;

    public ExternalPolicy(List<String> keep, List<String> drop, List<String> manual, String source) {
        this.keep = List.copyOf(keep);
        this.drop = List.copyOf(drop);
        this.manual = List.copyOf(manual);
        this.source = source;
    }

    /** No configuration: the default rule only. */
    public static ExternalPolicy defaults() {
        return new ExternalPolicy(List.of(), List.of(), List.of(), null);
    }

    /** Loads {@code file}, or the defaults when it does not exist. */
    public static ExternalPolicy load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return defaults();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ExternalPolicy(list(properties, "external.keep"), list(properties, "external.drop"),
                list(properties, "external.manual"), file.toString());
    }

    private static List<String> list(Properties properties, String key) {
        List<String> values = new ArrayList<>();
        for (String part : properties.getProperty(key, "").split("[,\\s]+")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    /**
     * @param member            {@code package.Class.method} (or {@code package.Class.<init>})
     * @param involvesSelenium  whether a Selenium object is passed to or returned from the call
     */
    public Decision decide(String member, boolean involvesSelenium) {
        if (matches(drop, member)) {
            return Decision.DROP;
        }
        if (matches(manual, member)) {
            return Decision.MANUAL;
        }
        if (matches(keep, member)) {
            return Decision.KEEP;
        }
        return involvesSelenium ? Decision.MANUAL : Decision.KEEP;
    }

    private static boolean matches(List<String> prefixes, String member) {
        for (String prefix : prefixes) {
            if (member.equals(prefix) || member.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Where the configuration came from, or null for the defaults. */
    public String source() {
        return source;
    }
}

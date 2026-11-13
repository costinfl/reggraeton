package io.reggraeton.model;

import java.util.List;

/**
 * Call graph from step definitions and hooks into the project's own code (page objects, helpers),
 * with the external (Selenium, assertion, other library) API calls each node makes.
 */
public record CallGraph(List<Node> nodes, List<Edge> edges) {

    /**
     * @param id        {@code com.x.Class#method(Type,Type)}, {@code com.x.Class#<init>(..)}, {@code com.x.Class} for the
     *                  class-level node (field locators), {@code com.x.Class#step@L12} for lambda step definitions
     * @param kind      {@code step}, {@code hook}, {@code method}, {@code constructor} or {@code class}
     * @param apiUsages external API calls made directly by this node
     */
    public record Node(String id, String kind, String className, String name, String file, int line,
                       List<ApiUsage> apiUsages) {
    }

    /** @param resolved false when the target was found by name/arity only (types could not be resolved) */
    public record Edge(String from, String to, int line, boolean resolved) {
    }

    /**
     * One external API call site.
     *
     * @param category {@link #SELENIUM}, {@link #ASSERTION}, {@link #CUCUMBER}, {@link #DRIVER_SETUP}, {@link #DATABASE} or {@link #EXTERNAL}
     * @param api      normalised name, e.g. {@code WebDriver.get}, {@code By.id}, {@code @FindBy}, {@code Select.<init>}
     * @param support  {@link #AUTO} when reggraeton has a translation rule, {@link #MANUAL} when a human must convert it
     */
    public record ApiUsage(String category, String api, String code, int line, String support, String note) {
        public static final String SELENIUM = "selenium";
        public static final String ASSERTION = "assertion";
        public static final String CUCUMBER = "cucumber";
        public static final String DRIVER_SETUP = "driver-setup";
        public static final String EXTERNAL = "external";
        /** External call kept as is: no Selenium object flows in or out, or listed in external.keep. */
        public static final String EXTERNAL_KEPT = "external-kept";
        /** External call removed from the generated code (external.drop). */
        public static final String EXTERNAL_DROPPED = "external-dropped";
        /** Database access (JDBC, Spring JDBC, JPA/Hibernate, Oracle driver ...) or SQL text; kept as is. */
        public static final String DATABASE = "database";
        /** Prefix of the {@link #api} of SQL found in string literals: {@code SQL INSERT}, {@code SQL NEXTVAL} ... */
        public static final String SQL_PREFIX = "SQL ";
        public static final String AUTO = "auto";
        public static final String MANUAL = "manual";
    }
}

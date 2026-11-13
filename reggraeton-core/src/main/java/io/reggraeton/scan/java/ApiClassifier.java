package io.reggraeton.scan.java;

import com.github.javaparser.ast.expr.MethodCallExpr;
import io.reggraeton.model.CallGraph.ApiUsage;

/**
 * Decides what an external (non-project) call is: Selenium, driver setup, assertion, Cucumber API,
 * JDK (ignored) or another library, and whether reggraeton can translate it.
 */
public final class ApiClassifier {

    private ApiClassifier() {
    }

    /**
     * @param declaringType fully qualified declaring type of the called member
     * @param member        method name, or {@code <init>} for constructors
     * @param call          the call expression when it is a method call (used for argument-sensitive rules)
     * @return the usage, or null for calls that need no conversion (JDK and friends)
     */
    public static ApiUsage classify(String declaringType, String member, String code, int line, MethodCallExpr call) {
        return classify(declaringType, member, code, line, call, ExternalPolicy.defaults());
    }

    /**
     * @param site   the call or object creation (used for argument-sensitive rules and Selenium type flow)
     * @param policy what to do with calls into libraries outside the scanned repository
     */
    public static ApiUsage classify(String declaringType, String member, String code, int line,
                                    com.github.javaparser.ast.expr.Expression site, ExternalPolicy policy) {
        MethodCallExpr call = site instanceof MethodCallExpr m ? m : null;
        String typeName = JavaSourceIndex.withoutPackage(declaringType);
        String api = typeName + "." + member;

        if (declaringType.equals("java.lang.Thread") && member.equals("sleep")) {
            return new ApiUsage("timing", "Thread.sleep", code, line, ApiUsage.AUTO,
                    "hard wait kept as is; consider a web-first assertion instead");
        }
        String database = databaseLibrary(declaringType);
        if (database != null) {
            return new ApiUsage(ApiUsage.DATABASE, api, maskCredentials(code), line, ApiUsage.AUTO,
                    "database access (" + database + "), kept as is");
        }
        if (isJdk(declaringType)) {
            return null;
        }
        if (declaringType.startsWith("io.github.bonigarcia.")) {
            return new ApiUsage(ApiUsage.DRIVER_SETUP, api, code, line, ApiUsage.AUTO,
                    "driver download/management is removed; the installed browser is used");
        }
        if (declaringType.startsWith("org.openqa.selenium.")) {
            if (isDriverSetup(declaringType, member)) {
                return new ApiUsage(ApiUsage.DRIVER_SETUP, api, code, line, ApiUsage.AUTO,
                        "browser startup is handled by the generated PlaywrightSession");
            }
            String normalised = SeleniumApiCatalog.normalise(api);
            SeleniumApiCatalog.Support support = SeleniumApiCatalog.classify(normalised, call);
            return new ApiUsage(ApiUsage.SELENIUM, normalised, code, line,
                    support.auto() ? ApiUsage.AUTO : ApiUsage.MANUAL, support.note());
        }
        if (isAssertion(declaringType)) {
            return new ApiUsage(ApiUsage.ASSERTION, api, code, line, ApiUsage.AUTO, "kept as is");
        }
        if (declaringType.startsWith("io.cucumber.") || declaringType.startsWith("cucumber.api.")) {
            return new ApiUsage(ApiUsage.CUCUMBER, api, code, line, ApiUsage.AUTO,
                    "kept in bdd mode, provided by the runtime support classes in native mode");
        }
        boolean selenium = site != null && SeleniumTypes.involved(site);
        return switch (policy.decide(declaringType + "." + member, selenium)) {
            case KEEP -> new ApiUsage(ApiUsage.EXTERNAL_KEPT, api, code, line, ApiUsage.AUTO,
                    "kept as is (no Selenium object passes through it); the original project's dependencies are kept");
            case DROP -> new ApiUsage(ApiUsage.EXTERNAL_DROPPED, api, code, line, ApiUsage.AUTO,
                    "removed from the generated code (external.drop in reggraeton.properties)");
            case MANUAL -> new ApiUsage(ApiUsage.EXTERNAL, api, code, line, ApiUsage.MANUAL, selenium
                    ? "library outside the scanned repository that receives or returns Selenium objects; convert by hand"
                    : "listed in external.manual (reggraeton.properties)");
        };
    }

    /** Library name for database access types, null for anything else. JDBC value types (Date, Timestamp) are not access. */
    static String databaseLibrary(String type) {
        if (type.startsWith("java.sql.") || type.startsWith("javax.sql.")) {
            return type.matches("java\\.sql\\.(Date|Time|Timestamp|Types|JDBCType|SQLType|.*Exception|.*Warning)") ? null : JDBC;
        }
        String[][] libraries = {
                {"oracle.jdbc.", "Oracle JDBC"}, {"oracle.sql.", "Oracle JDBC"}, {"oracle.ucp.", "Oracle UCP"},
                {"org.springframework.jdbc.", "Spring JDBC"}, {"org.springframework.orm.", "Spring ORM"},
                {"org.springframework.transaction.", "Spring transactions"}, {"org.springframework.data.jpa.", "Spring Data JPA"},
                {"org.springframework.data.repository.", "Spring Data"},
                {"org.hibernate.", "Hibernate"}, {"javax.persistence.", "JPA"}, {"jakarta.persistence.", "JPA"},
                {"org.dbunit.", "DbUnit"}, {"org.apache.ibatis.", "MyBatis"}, {"org.mybatis.", "MyBatis"},
                {"org.jooq.", "jOOQ"}, {"org.jdbi.", "Jdbi"}, {"org.sql2o.", "Sql2o"}, {"org.flywaydb.", "Flyway"},
                {"liquibase.", "Liquibase"}, {"com.zaxxer.hikari.", "HikariCP"}, {"org.apache.commons.dbcp", "Commons DBCP"},
                {"org.apache.commons.dbutils.", "Commons DbUtils"}, {"com.mchange.v2.c3p0.", "c3p0"},
                {"org.apache.tomcat.jdbc.", "Tomcat JDBC"}, {"com.github.database.rider.", "Database Rider"},
                {"org.testcontainers.containers.", "Testcontainers"}};
        for (String[] library : libraries) {
            if (type.startsWith(library[0])) {
                return library[1];
            }
        }
        return null;
    }

    /** Name used for JDK database access; such code needs no extra dependency. */
    public static final String JDBC = "JDBC";

    /** getConnection(url, user, "secret") and passwords in JDBC URLs must not end up in reports. */
    static String maskCredentials(String code) {
        String s = code.replaceAll("(getConnection\\s*\\([^,]*,[^,]*,\\s*)\"[^\"]*\"", "$1\"***\"");
        s = s.replaceAll("(?i)(setPassword\\s*\\(\\s*)\"[^\"]*\"", "$1\"***\"");
        return io.reggraeton.scan.db.Connections.mask(s);
    }

    private static boolean isDriverSetup(String declaringType, String member) {
        String pkg = declaringType.substring(0, Math.max(0, declaringType.lastIndexOf('.')));
        boolean driverPackage = pkg.matches("org\\.openqa\\.selenium\\.(chrome|chromium|edge|firefox|safari|ie|remote|remote\\.service)");
        if (!driverPackage) {
            return false;
        }
        String simple = declaringType.substring(declaringType.lastIndexOf('.') + 1);
        if (simple.equals("Augmenter")) {
            return false; // new Augmenter().augment(driver): translated, not driver setup
        }
        return member.equals("<init>") || simple.endsWith("Options") || simple.endsWith("Service")
                || simple.endsWith("Capabilities");
    }

    private static boolean isJdk(String type) {
        return type.startsWith("java.") || type.startsWith("javax.") || type.startsWith("jdk.")
                || type.startsWith("sun.") || type.startsWith("com.sun.")
                || type.startsWith("org.slf4j.") || type.startsWith("org.apache.logging.")
                || type.startsWith("org.apache.commons.lang") || type.startsWith("com.google.common.");
    }

    private static boolean isAssertion(String type) {
        return type.startsWith("org.junit.") || type.startsWith("junit.framework.") || type.startsWith("org.assertj.")
                || type.startsWith("org.hamcrest.") || type.startsWith("org.testng.Assert")
                || type.startsWith("org.testng.asserts.") || type.startsWith("com.google.common.truth.");
    }
}

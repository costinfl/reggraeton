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
        String typeName = JavaSourceIndex.withoutPackage(declaringType);
        String api = typeName + "." + member;

        if (declaringType.equals("java.lang.Thread") && member.equals("sleep")) {
            return new ApiUsage("timing", "Thread.sleep", code, line, ApiUsage.AUTO,
                    "hard wait kept as page.waitForTimeout; prefer a web-first assertion");
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
        return new ApiUsage(ApiUsage.EXTERNAL, api, code, line, ApiUsage.MANUAL,
                "library outside the scanned repository; reggraeton cannot follow or translate it");
    }

    private static boolean isDriverSetup(String declaringType, String member) {
        String pkg = declaringType.substring(0, Math.max(0, declaringType.lastIndexOf('.')));
        boolean driverPackage = pkg.matches("org\\.openqa\\.selenium\\.(chrome|chromium|edge|firefox|safari|ie|remote|remote\\.service)");
        if (!driverPackage) {
            return false;
        }
        String simple = declaringType.substring(declaringType.lastIndexOf('.') + 1);
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

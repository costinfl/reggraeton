package io.reggraeton.scan.java;

import java.util.Set;
import java.util.regex.Pattern;

/** Knows which annotations / java8 methods are Cucumber glue, for current and legacy Cucumber-JVM. */
public final class GlueAnnotations {

    /** {@code io.cucumber.java.en.Given}, {@code cucumber.api.java.de.Angenommen}, ... */
    private static final Pattern STEP_ANNOTATION = Pattern.compile(
            "(?:io\\.cucumber\\.java|cucumber\\.api\\.java)\\.[a-z_]+(?:_[a-z]+)?\\.[\\p{L}_*]+");

    private static final Set<String> ROOT_PACKAGES = Set.of("io.cucumber.java", "cucumber.api.java");

    public static final Set<String> HOOK_PHASES = Set.of(
            "Before", "After", "BeforeStep", "AfterStep", "BeforeAll", "AfterAll");

    /** English keywords, used when an annotation cannot be qualified through the imports. */
    public static final Set<String> ENGLISH_KEYWORDS = Set.of("Given", "When", "Then", "And", "But");

    /** java8 lambda-glue method names (English) that register steps. */
    public static final Set<String> LAMBDA_STEP_METHODS = ENGLISH_KEYWORDS;

    private GlueAnnotations() {
    }

    public static boolean isStepAnnotation(String qualifiedName) {
        // Repeated annotations (@Given("a") @Given("b")) appear individually in source and are
        // handled one by one; the explicit container form (@Given.Givens({...})) is not supported.
        return STEP_ANNOTATION.matcher(qualifiedName).matches();
    }

    public static boolean isUnqualifiedStepKeyword(String name) {
        return ENGLISH_KEYWORDS.contains(name);
    }

    /** Hook phase ({@code Before}, ...) for a hook annotation, or null. */
    public static String hookPhase(String qualifiedName) {
        for (String root : ROOT_PACKAGES) {
            if (qualifiedName.startsWith(root + ".")) {
                String simple = qualifiedName.substring(root.length() + 1);
                if (HOOK_PHASES.contains(simple)) {
                    return simple;
                }
            }
        }
        return null;
    }

    public static boolean isParameterType(String qualifiedName) {
        return qualifiedName.equals("io.cucumber.java.ParameterType");
    }

    /** {@code DataTableType} / {@code DocStringType} / {@code DefaultParameterTransformer} ..., or null. */
    public static String transformerKind(String qualifiedName) {
        if (!qualifiedName.startsWith("io.cucumber.java.")) {
            return null;
        }
        String simple = qualifiedName.substring("io.cucumber.java.".length());
        return switch (simple) {
            case "DataTableType", "DocStringType", "DefaultParameterTransformer",
                 "DefaultDataTableEntryTransformer", "DefaultDataTableCellTransformer" -> simple;
            default -> null;
        };
    }

    public static boolean isGluePackage(String pkg) {
        return ROOT_PACKAGES.contains(pkg) || pkg.startsWith("io.cucumber.java.") || pkg.startsWith("cucumber.api.java.");
    }

    /** Whether {@code simpleName} plausibly lives in glue package {@code pkg} (for wildcard imports). */
    public static boolean isKnownSimpleName(String pkg, String simpleName) {
        if (ROOT_PACKAGES.contains(pkg)) {
            return HOOK_PHASES.contains(simpleName) || simpleName.equals("ParameterType")
                    || transformerKind("io.cucumber.java." + simpleName) != null;
        }
        // language packages contain only step keywords
        return Character.isUpperCase(simpleName.charAt(0));
    }

    /** Keyword part of a step annotation name: {@code io.cucumber.java.en.Given} gives {@code Given}. */
    public static String keyword(String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        return simple;
    }
}

package io.reggraeton.naming;

import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.ScenarioDefinition;
import io.reggraeton.model.TestCase;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the names of generated tests, used by the assessment's traceability
 * report and by the generators, so the report always names the tests that {@code convert} writes.
 *
 * <ul>
 *   <li>native mode: one JUnit class per feature file, one method per scenario; a Scenario Outline is
 *       one {@code @ParameterizedTest} method and each Examples row is one invocation ({@code [n]}).</li>
 *   <li>bdd mode: the feature file is copied unchanged, so a test is addressed as {@code path:line}.</li>
 * </ul>
 */
public final class TestNaming {

    /** Location of one generated test. */
    public record GeneratedTest(
            String testCaseId,
            String nativeClass,
            String nativeMethod,
            Integer invocation,
            String bddFeature,
            int bddLine) {

        /** {@code pkg.Class#method} or {@code pkg.Class#method[3]}. */
        public String nativeName() {
            return nativeClass + "#" + nativeMethod + (invocation == null ? "" : "[" + invocation + "]");
        }

        /** Maven command running only this test in a native-mode project. */
        public String nativeCommand() {
            return "mvn test -Dtest=" + nativeClass.substring(nativeClass.lastIndexOf('.') + 1) + "#" + nativeMethod;
        }

        /** Maven command running only this test in a bdd-mode project. */
        public String bddCommand() {
            return "mvn test -Dcucumber.features=" + bddFeature + ":" + bddLine;
        }
    }

    public static final String DEFAULT_BASE_PACKAGE = "regression";
    /** Where feature files are placed in a generated bdd project. */
    public static final String BDD_FEATURE_ROOT = "src/test/resources/features";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed", "permits");

    private final String basePackage;
    private final Map<String, String> classByFeature = new LinkedHashMap<>();
    private final Map<String, String> methodByScenario = new HashMap<>();
    private final Map<String, String> featureTargetPath = new HashMap<>();

    public TestNaming(Inventory inventory, String basePackage) {
        this.basePackage = basePackage == null || basePackage.isBlank() ? DEFAULT_BASE_PACKAGE : basePackage;
        assign(inventory);
    }

    public String basePackage() {
        return basePackage;
    }

    private void assign(Inventory inventory) {
        Set<String> usedClasses = new HashSet<>();
        Set<String> usedTargets = new HashSet<>();
        String commonRoot = commonDirectory(inventory.features().stream()
                .filter(f -> f.parseError() == null).map(FeatureFile::path).toList());
        for (FeatureFile feature : inventory.features()) {
            if (feature.parseError() != null) {
                continue;
            }
            String relative = compact(commonRoot.isEmpty() ? feature.path() : feature.path().substring(commonRoot.length() + 1));
            String target = relative;
            for (int i = 2; !usedTargets.add(target.toLowerCase(Locale.ROOT)); i++) {
                target = relative.replaceFirst("\\.feature$", "_" + i + ".feature");
            }
            relative = target;
            featureTargetPath.put(feature.path(), BDD_FEATURE_ROOT + "/" + relative);

            String dir = relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : "";
            String sub = dir.isEmpty() ? "" : packagePath(dir);
            String pkg = basePackage + ".features" + (sub.isEmpty() ? "" : "." + sub);
            String fileName = relative.substring(relative.lastIndexOf('/') + 1).replaceFirst("\\.feature$", "");
            String base = typeName(feature.name() == null || feature.name().isBlank() ? fileName : feature.name());
            String candidate = pkg + "." + base + "Test";
            for (int i = 2; !usedClasses.add(candidate.toLowerCase(Locale.ROOT)); i++) {
                candidate = pkg + "." + base + i + "Test";
            }
            classByFeature.put(feature.path(), candidate);

            Set<String> usedMethods = new HashSet<>();
            for (ScenarioDefinition scenario : feature.scenarios()) {
                String method = memberName(scenario.name().isBlank() ? "scenario line " + scenario.line() : scenario.name());
                String unique = method;
                for (int i = 2; !usedMethods.add(unique); i++) {
                    unique = method + "_" + i;
                }
                methodByScenario.put(feature.path() + ":" + scenario.line(), unique);
            }
        }
    }

    public GeneratedTest of(TestCase testCase) {
        String cls = classByFeature.get(testCase.feature());
        String method = methodByScenario.get(testCase.feature() + ":" + testCase.scenarioLine());
        int bddLine = testCase.exampleLine() != null ? testCase.exampleLine() : testCase.scenarioLine();
        return new GeneratedTest(testCase.id(), cls, method, testCase.exampleIndex(),
                featureTargetPath.get(testCase.feature()), bddLine);
    }

    public List<GeneratedTest> all(List<TestCase> testCases) {
        List<GeneratedTest> result = new ArrayList<>();
        testCases.forEach(tc -> result.add(of(tc)));
        return result;
    }

    /** Fully qualified native test class for a feature file path, or null if the feature did not parse. */
    public String classFor(String featurePath) {
        return classByFeature.get(featurePath);
    }

    public String methodFor(String featurePath, int scenarioLine) {
        return methodByScenario.get(featurePath + ":" + scenarioLine);
    }

    /** Path of the feature file inside a generated bdd project. */
    public String bddFeaturePath(String featurePath) {
        return featureTargetPath.get(featurePath);
    }

    // ----------------------------------------------------------------------------------------
    // Identifier helpers
    // ----------------------------------------------------------------------------------------

    /** Deepest directory containing all {@code paths} ({@code ""} for the repository root). */
    static String commonDirectory(List<String> paths) {
        if (paths.isEmpty()) {
            return "";
        }
        String[] common = dirOf(paths.get(0)).split("/");
        int length = dirOf(paths.get(0)).isEmpty() ? 0 : common.length;
        for (String path : paths) {
            String dir = dirOf(path);
            String[] parts = dir.isEmpty() ? new String[0] : dir.split("/");
            int i = 0;
            while (i < length && i < parts.length && parts[i].equals(common[i])) {
                i++;
            }
            length = i;
        }
        return String.join("/", java.util.Arrays.copyOf(common, length));
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Drops {@code src/<set>/resources/} segments, which are noise in multi-module layouts. */
    private static String compact(String relative) {
        return relative.replaceAll("(^|/)src/[^/]+/resources/", "$1");
    }

    private static List<String> words(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> words = new ArrayList<>();
        for (String w : ascii.split("[^A-Za-z0-9]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
    }

    /** {@code "Shopping cart"} gives {@code ShoppingCart}. */
    public static String typeName(String text) {
        StringBuilder sb = new StringBuilder();
        for (String w : words(text)) {
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        String name = sb.length() == 0 ? "Feature" : sb.toString();
        return Character.isDigit(name.charAt(0)) ? "F" + name : name;
    }

    /** {@code "Add a single product"} gives {@code addASingleProduct}. */
    public static String memberName(String text) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return "scenario";
        }
        StringBuilder sb = new StringBuilder(words.get(0).toLowerCase(Locale.ROOT));
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        String name = sb.toString();
        if (Character.isDigit(name.charAt(0))) {
            name = "scenario" + name;
        }
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        return JAVA_KEYWORDS.contains(name) ? name + "Scenario" : name;
    }

    private static String packagePath(String dir) {
        List<String> parts = new ArrayList<>();
        for (String segment : dir.split("/")) {
            String p = String.join("", words(segment)).toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (Character.isDigit(p.charAt(0)) || JAVA_KEYWORDS.contains(p)) {
                p = "_" + p;
            }
            parts.add(p);
        }
        return String.join(".", parts);
    }
}

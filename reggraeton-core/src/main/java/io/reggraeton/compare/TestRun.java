package io.reggraeton.compare;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The results of one test run, read from JUnit XML reports ({@code TEST-*.xml}, as written by Maven
 * Surefire, Gradle and the Jenkins junit step).
 */
public final class TestRun {

    /** Outcome of one test. */
    public enum Status { PASSED, FAILED, ERROR, SKIPPED }

    /**
     * One test.
     *
     * @param key         identity used to match the same test across runs
     * @param reportClass class the report file is named after ({@code TEST-<reportClass>.xml})
     * @param testCaseId  original scenario id ({@code feature:line}) when it can be found in traceability.csv
     * @param message     failure message (first lines), null when passed
     */
    public record Result(String key, String reportClass, String className, String name, Status status,
                         String message, double seconds, String testCaseId) {

        public boolean failed() {
            return status == Status.FAILED || status == Status.ERROR;
        }
    }

    private final Path location;
    private final Map<String, Result> results;

    private TestRun(Path location, Map<String, Result> results) {
        this.location = location;
        this.results = results;
    }

    /**
     * Reads every {@code TEST-*.xml} below {@code location} (a project directory, a
     * {@code surefire-reports} directory, or a single report file).
     */
    public static TestRun read(Path location) {
        Path root = location.toAbsolutePath().normalize();
        List<Path> reports = Files.isRegularFile(root) ? List.of(root)
                : reports(root);
        Map<String, String> traceability = Traceability.load(Files.isDirectory(root) ? root : root.getParent());
        Map<String, Result> results = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Path report : reports) {
            String file = report.getFileName().toString();
            String reportClass = file.substring("TEST-".length(), file.length() - ".xml".length());
            for (Element testcase : testcases(report)) {
                String className = testcase.getAttribute("classname");
                String name = testcase.getAttribute("name");
                String base = reportClass + " | " + className + " | " + name;
                int n = occurrences.merge(base, 1, Integer::sum);
                String key = n == 1 ? base : base + " #" + n;
                Status status = Status.PASSED;
                String message = null;
                Element problem = child(testcase, "failure");
                if (problem != null) {
                    status = Status.FAILED;
                } else if ((problem = child(testcase, "error")) != null) {
                    status = Status.ERROR;
                } else if (child(testcase, "skipped") != null) {
                    status = Status.SKIPPED;
                    message = child(testcase, "skipped").getAttribute("message");
                }
                if (problem != null) {
                    message = problem.getAttribute("message");
                    if (message.isBlank()) {
                        message = problem.getTextContent();
                    }
                    message = firstLines(message, 4);
                }
                double seconds = parseDouble(testcase.getAttribute("time"));
                results.put(key, new Result(key, reportClass, className, name, status, message, seconds,
                        traceability.get(nativeTestName(reportClass, name))));
            }
        }
        return new TestRun(root, results);
    }

    public Path location() {
        return location;
    }

    public Map<String, Result> results() {
        return results;
    }

    /** {@code regression.features.LoginTest} + {@code rejectedLogin(int)[3]} gives {@code ...LoginTest#rejectedLogin[3]}. */
    static String nativeTestName(String reportClass, String name) {
        String method = name.replaceAll("\\(.*?\\)", "");
        return reportClass + "#" + method;
    }

    /** TEST-*.xml below root, including build output folders (target/surefire-reports). */
    private static List<Path> reports(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("No such directory or file: " + root);
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').matches("(?!.*(^|/)(\\.git|node_modules)/).*"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<Element> testcases(Path report) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(report.toFile());
            NodeList list = doc.getElementsByTagName("testcase");
            List<Element> result = new ArrayList<>();
            for (int i = 0; i < list.getLength(); i++) {
                result.add((Element) list.item(i));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read JUnit report " + report + ": " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String name) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static String firstLines(String text, int lines) {
        String[] parts = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines, parts.length); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(parts[i].strip());
        }
        return sb.toString();
    }

    private static double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Maps native test names to original test case ids using a generated project's traceability.csv. */
    static final class Traceability {
        private Traceability() {
        }

        static Map<String, String> load(Path dir) {
            Map<String, String> map = new HashMap<>();
            for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
                Path csv = candidate.resolve("traceability.csv");
                if (Files.isRegularFile(csv)) {
                    try {
                        List<String> lines = Files.readAllLines(csv);
                        List<String> header = parse(lines.get(0));
                        int id = header.indexOf("test_case_id");
                        int nativeTest = header.indexOf("native_test");
                        for (String line : lines.subList(1, lines.size())) {
                            List<String> fields = parse(line);
                            if (id >= 0 && nativeTest >= 0 && fields.size() > Math.max(id, nativeTest)) {
                                map.put(fields.get(nativeTest), fields.get(id));
                            }
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        // optional information only
                    }
                    break;
                }
            }
            return map;
        }

        /** Minimal RFC 4180 field splitter. */
        static List<String> parse(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        sb.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
            fields.add(sb.toString());
            return fields;
        }
    }
}

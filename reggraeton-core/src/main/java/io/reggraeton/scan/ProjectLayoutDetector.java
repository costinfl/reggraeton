package io.reggraeton.scan;

import io.reggraeton.model.ProjectLayout;
import io.reggraeton.util.RepoFiles;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Works out how a Cucumber-JVM repository is organised: build tool, source roots, where the
 * feature files live, which glue packages the runners declare and which DI container is used.
 * Uses lightweight text/XML inspection only; nothing in the scanned repository is executed.
 */
public final class ProjectLayoutDetector {

    private static final Pattern SOURCE_ROOT = Pattern.compile("(^|.*/)src/[^/]+/java$");

    private static final Pattern GLUE_JUNIT5 = Pattern.compile(
            "(?:GLUE_PROPERTY_NAME|\"cucumber\\.glue\")\\s*,\\s*value\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern GLUE_OPTIONS = Pattern.compile(
            "glue\\s*=\\s*(\\{[^}]*}|\"[^\"]*\")");
    private static final Pattern GLUE_PROPERTIES = Pattern.compile(
            "(?m)^\\s*cucumber\\.glue\\s*[=:]\\s*(.+)$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern RUNNER_MARKER = Pattern.compile(
            "@IncludeEngines\\(\\s*\"cucumber\"\\s*\\)|@CucumberOptions|Cucumber\\.class|AbstractTestNGCucumberTests");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "[\"']([\\w.\\-]+):([\\w.\\-]+):([^\"'@]+)[\"']");
    private static final Pattern MAVEN_PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    public ProjectLayout detect(Path root) {
        Path absRoot = root.toAbsolutePath().normalize();

        List<Path> buildFiles = RepoFiles.find(absRoot, p -> {
            String n = p.getFileName().toString();
            return n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts");
        });
        String buildTool = buildFiles.stream().anyMatch(p -> p.getFileName().toString().equals("pom.xml")) ? "maven"
                : buildFiles.isEmpty() ? "none" : "gradle";

        List<Path> javaFiles = RepoFiles.findByExtension(absRoot, ".java");
        List<String> sourceRoots = javaFiles.stream()
                .map(f -> sourceRootOf(absRoot, f))
                .filter(s -> s != null)
                .distinct().sorted().collect(Collectors.toList());

        List<String> featureDirs = RepoFiles.findByExtension(absRoot, ".feature").stream()
                .map(f -> RepoFiles.relative(absRoot, f.getParent()))
                .distinct().sorted().collect(Collectors.toList());

        Set<String> glue = new LinkedHashSet<>();
        List<String> runners = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            String text = RepoFiles.read(javaFile);
            if (RUNNER_MARKER.matcher(text).find()) {
                runners.add(RepoFiles.relative(absRoot, javaFile));
                collectGlue(text, glue);
            }
        }
        for (Path props : RepoFiles.find(absRoot, p -> {
            String n = p.getFileName().toString();
            return n.equals("junit-platform.properties") || n.equals("cucumber.properties");
        })) {
            Matcher m = GLUE_PROPERTIES.matcher(RepoFiles.read(props));
            while (m.find()) {
                addGlue(m.group(1), glue);
            }
        }

        Map<String, String> libraries = new TreeMap<>();
        for (Path buildFile : buildFiles) {
            if (buildFile.getFileName().toString().equals("pom.xml")) {
                readMavenDependencies(buildFile, libraries);
            } else {
                Matcher m = GRADLE_DEPENDENCY.matcher(RepoFiles.read(buildFile));
                while (m.find()) {
                    libraries.putIfAbsent(m.group(1) + ":" + m.group(2), m.group(3));
                }
            }
        }

        return new ProjectLayout(
                absRoot.toString(),
                buildTool,
                buildFiles.stream().map(f -> RepoFiles.relative(absRoot, f)).collect(Collectors.toList()),
                sourceRoots,
                minimalRoots(featureDirs),
                new ArrayList<>(glue),
                runners,
                dependencyInjection(libraries),
                libraries);
    }

    private static String sourceRootOf(Path root, Path javaFile) {
        for (Path dir = javaFile.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            String rel = RepoFiles.relative(root, dir);
            if (SOURCE_ROOT.matcher(rel).matches()) {
                return rel;
            }
        }
        return null;
    }

    /** Keeps only directories that are not below another directory of the list. */
    static List<String> minimalRoots(List<String> sortedDirs) {
        List<String> roots = new ArrayList<>();
        for (String dir : sortedDirs) {
            boolean covered = roots.stream().anyMatch(r -> r.isEmpty() || dir.equals(r) || dir.startsWith(r + "/"));
            if (!covered) {
                roots.add(dir);
            }
        }
        return roots;
    }

    private static void collectGlue(String text, Set<String> glue) {
        Matcher m = GLUE_JUNIT5.matcher(text);
        while (m.find()) {
            addGlue(m.group(1), glue);
        }
        m = GLUE_OPTIONS.matcher(text);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) {
                addGlue(q.group(1), glue);
            }
        }
    }

    private static void addGlue(String value, Set<String> glue) {
        for (String part : value.split(",")) {
            String p = part.trim();
            if (p.startsWith("classpath:")) {
                p = p.substring("classpath:".length());
            }
            p = p.replace('/', '.');
            while (p.startsWith(".")) {
                p = p.substring(1);
            }
            if (!p.isEmpty()) {
                glue.add(p);
            }
        }
    }

    private static String dependencyInjection(Map<String, String> libraries) {
        boolean cucumber = libraries.keySet().stream().anyMatch(k -> k.startsWith("io.cucumber:"));
        if (libraries.containsKey("io.cucumber:cucumber-picocontainer")) {
            return "picocontainer";
        }
        if (libraries.containsKey("io.cucumber:cucumber-spring")) {
            return "spring";
        }
        if (libraries.containsKey("io.cucumber:cucumber-guice")) {
            return "guice";
        }
        return cucumber ? "none" : "unknown";
    }

    // ----------------------------------------------------------------------------------------

    private static void readMavenDependencies(Path pom, Map<String, String> libraries) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            doc = factory.newDocumentBuilder().parse(pom.toFile());
        } catch (Exception e) {
            return; // unreadable pom: layout detection is best effort
        }
        Element project = doc.getDocumentElement();
        Map<String, String> properties = new HashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            NodeList list = props.getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                if (list.item(i) instanceof Element e) {
                    properties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
        }
        NodeList dependencies = project.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String group = text(dep, "groupId");
            String artifact = text(dep, "artifactId");
            if (group == null || artifact == null) {
                continue;
            }
            String version = resolve(text(dep, "version"), properties);
            String key = resolve(group, properties) + ":" + resolve(artifact, properties);
            String previous = libraries.get(key);
            if (previous == null || "?".equals(previous)) {
                libraries.put(key, version == null ? "?" : version);
            }
        }
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        Matcher m = MAVEN_PROPERTY.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = properties.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
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

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}

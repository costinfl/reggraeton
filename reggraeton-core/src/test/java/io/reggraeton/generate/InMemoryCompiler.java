package io.reggraeton.generate;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compiles generated sources with the test classpath (Playwright, Cucumber, JUnit) to prove they are valid Java. */
final class InMemoryCompiler {

    private InMemoryCompiler() {
    }

    /** @param sources relative path (below src/test/java) to source; returns compiler errors, empty when it compiles */
    static List<String> compile(Map<String, String> sources, Path workDir) {
        try {
            Path src = workDir.resolve("src");
            Path out = workDir.resolve("classes");
            Files.createDirectories(out);
            List<Path> files = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                Path file = src.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                files.add(file);
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
                List<String> options = List.of("-encoding", "UTF-8", "-d", out.toString(), "-classpath", System.getProperty("java.class.path"),
                        "--release", "17", "-proc:none", "-Xlint:none");
                boolean ok = compiler.getTask(null, fm, diagnostics, options, null,
                        fm.getJavaFileObjectsFromPaths(files)).call();
                List<String> errors = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(d -> (d.getSource() == null ? "" : src.relativize(Path.of(d.getSource().toUri())) + ":" + d.getLineNumber() + ": ")
                                + d.getMessage(null))
                        .collect(Collectors.toList());
                if (!ok && errors.isEmpty()) {
                    errors.add("compilation failed without diagnostics");
                }
                return errors;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

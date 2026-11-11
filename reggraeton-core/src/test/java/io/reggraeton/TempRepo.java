package io.reggraeton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tiny builder for throw-away repositories in unit tests. */
public final class TempRepo {

    private final Path root;

    public TempRepo(Path root) {
        this.root = root;
    }

    public TempRepo file(String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds a Java source under {@code src/test/java}, deriving the path from package and class name. */
    public TempRepo java(String packageName, String className, String body) {
        String path = "src/test/java/" + packageName.replace('.', '/') + "/" + className + ".java";
        return file(path, "package " + packageName + ";\n\n" + body);
    }

    public Path root() {
        return root;
    }
}

package io.reggraeton.cli;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses the {@code --classpath} option. */
final class Classpath {

    private Classpath() {
    }

    static List<Path> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String entries = value;
        Path asFile = Path.of(value);
        if (Files.isRegularFile(asFile) && !value.endsWith(".jar")) {
            try {
                entries = Files.readString(asFile).trim();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<Path> jars = new ArrayList<>();
        for (String entry : entries.split(File.pathSeparator)) {
            String e = entry.trim();
            if (!e.isEmpty() && e.endsWith(".jar") && Files.isRegularFile(Path.of(e))) {
                jars.add(Path.of(e));
            }
        }
        return jars;
    }
}

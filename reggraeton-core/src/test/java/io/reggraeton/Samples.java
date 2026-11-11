package io.reggraeton;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the sample repositories under {@code samples/}. */
public final class Samples {

    private Samples() {
    }

    public static Path legacyShop() {
        return root().resolve("legacy-shop");
    }

    public static Path root() {
        String configured = System.getProperty("reggraeton.samples");
        Path path = configured != null ? Path.of(configured) : Path.of("..", "samples");
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("samples directory not found: " + path.toAbsolutePath());
        }
        return path.toAbsolutePath().normalize();
    }
}

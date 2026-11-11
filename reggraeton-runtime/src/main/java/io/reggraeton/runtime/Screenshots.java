package io.reggraeton.runtime;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Bridges Selenium's {@code getScreenshotAs(OutputType.FILE / BASE64)} to Playwright's byte[] screenshots. */
public final class Screenshots {

    private Screenshots() {
    }

    public static File toFile(byte[] png) {
        try {
            Path file = Files.createTempFile("screenshot", ".png");
            Files.write(file, png);
            return file.toFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String toBase64(byte[] png) {
        return Base64.getEncoder().encodeToString(png);
    }
}

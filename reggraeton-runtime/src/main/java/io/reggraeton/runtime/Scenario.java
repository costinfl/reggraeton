package io.reggraeton.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * native mode: stands in for {@code io.cucumber.java.Scenario} in converted hooks and steps.
 * Attachments are written to the artifacts folder.
 */
public final class Scenario {

    private final String id;
    private final String name;
    private final List<String> tags;
    private boolean failed;

    public Scenario(String id, String name, List<String> tags) {
        this.id = id;
        this.name = name;
        this.tags = List.copyOf(tags);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Collection<String> getSourceTagNames() {
        return tags;
    }

    /** {@code feature path:line}, the same id the traceability report uses. */
    public String getUri() {
        int colon = id.lastIndexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    public Integer getLine() {
        int colon = id.lastIndexOf(':');
        try {
            return colon < 0 ? null : Integer.valueOf(id.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isFailed() {
        return failed;
    }

    /** {@code PASSED} or {@code FAILED} (as text: Cucumber's Status enum is not available in native mode). */
    public String getStatus() {
        return failed ? "FAILED" : "PASSED";
    }

    void markFailed() {
        this.failed = true;
    }

    public void attach(byte[] data, String mediaType, String attachmentName) {
        try {
            Path dir = RunConfig.artifactsDir();
            Files.createDirectories(dir);
            String extension = switch (mediaType) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "text/plain" -> ".txt";
                case "application/json" -> ".json";
                case "text/html" -> ".html";
                default -> ".bin";
            };
            Path file = dir.resolve(PlaywrightSession.safeFileName(name + "-" + attachmentName) + extension);
            Files.write(file, data);
            System.err.println("[reggraeton] attachment for '" + name + "': " + file);
        } catch (IOException e) {
            System.err.println("[reggraeton] could not write attachment: " + e.getMessage());
        }
    }

    public void attach(String data, String mediaType, String attachmentName) {
        attach(data.getBytes(StandardCharsets.UTF_8), mediaType, attachmentName);
    }

    public void log(String text) {
        System.out.println("[" + name + "] " + text);
    }
}

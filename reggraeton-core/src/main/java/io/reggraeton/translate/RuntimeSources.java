package io.reggraeton.translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sources of the runtime support classes ({@code reggraeton-runtime}), packaged inside
 * reggraeton, re-packaged for a generated project.
 */
public final class RuntimeSources {

    private static final String ORIGINAL_PACKAGE = "io.reggraeton.runtime";

    private RuntimeSources() {
    }

    /** Common support classes: simple class name to source, with {@code package} set to {@code targetPackage}. */
    public static Map<String, String> load(String targetPackage) {
        return load(targetPackage, SupportNames.RUNTIME_CLASSES);
    }

    public static Map<String, String> load(String targetPackage, java.util.Collection<String> classNames) {
        Map<String, String> sources = new TreeMap<>();
        for (String name : classNames) {
            String resource = "/reggraeton/runtime-sources/" + name + ".java";
            try (InputStream in = RuntimeSources.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing packaged runtime source " + resource
                            + " (build reggraeton with Maven so the runtime sources are copied)");
                }
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                sources.put(name, source.replace("package " + ORIGINAL_PACKAGE + ";", "package " + targetPackage + ";"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }
}

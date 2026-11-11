package io.reggraeton.translate;

import java.util.List;
import java.util.Map;

/**
 * One translated Java file.
 *
 * @param originalPath repo-relative path of the source it was translated from
 * @param relativePath path below the generated project's {@code src/test/java}
 * @param changes      applied rewrites, rule name to count
 * @param todos        places that still need a human
 */
public record TranslatedSource(
        String originalPath,
        String relativePath,
        String packageName,
        String primaryType,
        String source,
        Map<String, Integer> changes,
        List<Todo> todos) {

    /**
     * A {@code TODO(reggraeton)} stub left in the generated code.
     *
     * @param originalFile repo-relative path of the legacy file
     * @param line         line in the legacy file
     */
    public record Todo(String originalFile, int line, String api, String note, String code) {
    }
}

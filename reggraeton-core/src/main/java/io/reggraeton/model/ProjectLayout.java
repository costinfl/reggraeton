package io.reggraeton.model;

import java.util.List;
import java.util.Map;

/**
 * What reggraeton learned about the structure of the scanned repository.
 *
 * @param root                absolute path of the scanned repository
 * @param buildTool           {@code maven}, {@code gradle} or {@code none}
 * @param buildFiles          build files found (repo-relative)
 * @param javaSourceRoots     {@code src/main/java}, {@code src/test/java} ... directories (repo-relative)
 * @param featureRoots        smallest set of directories containing every {@code .feature} file (repo-relative)
 * @param gluePackages        glue packages declared by runners / properties; empty when none were declared
 * @param runners             Cucumber runner classes (repo-relative source files)
 * @param dependencyInjection {@code picocontainer}, {@code spring}, {@code guice}, {@code none} or {@code unknown}
 * @param libraries           detected test libraries, artifactId to version (version may be {@code ?})
 */
public record ProjectLayout(
        String root,
        String buildTool,
        List<String> buildFiles,
        List<String> javaSourceRoots,
        List<String> featureRoots,
        List<String> gluePackages,
        List<String> runners,
        String dependencyInjection,
        Map<String, String> libraries) {
}

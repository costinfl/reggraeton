package io.reggraeton.generate;

import io.reggraeton.naming.TestNaming;

import java.nio.file.Path;

/**
 * Options of {@code reggraeton convert}.
 *
 * @param output      directory of the generated Maven project
 * @param mode        {@link Mode#BDD} or {@link Mode#NATIVE}
 * @param basePackage package of the generated tests and support classes
 * @param groupId     Maven groupId of the generated project
 * @param artifactId  Maven artifactId of the generated project
 */
public record ConvertOptions(Path output, Mode mode, String basePackage, String groupId, String artifactId) {

    /** Output shape. */
    public enum Mode {
        /** Feature files are kept and run by Cucumber; glue code is translated to Playwright. */
        BDD,
        /** Plain JUnit 5 tests, one per scenario / Examples row; no Cucumber at run time. */
        NATIVE
    }

    public ConvertOptions {
        basePackage = basePackage == null || basePackage.isBlank() ? TestNaming.DEFAULT_BASE_PACKAGE : basePackage;
        groupId = groupId == null || groupId.isBlank() ? basePackage : groupId;
        artifactId = artifactId == null || artifactId.isBlank() ? "playwright-regression-tests" : artifactId;
    }

    public String supportPackage() {
        return basePackage + ".support";
    }
}

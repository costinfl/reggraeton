package io.reggraeton.scan;

import io.reggraeton.Samples;
import io.reggraeton.model.ProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectLayoutDetectorTest {

    private final ProjectLayoutDetector detector = new ProjectLayoutDetector();

    @Test
    void detectsLegacyShopLayout() {
        ProjectLayout layout = detector.detect(Samples.legacyShop());

        assertThat(layout.buildTool()).isEqualTo("maven");
        assertThat(layout.buildFiles()).containsExactly("pom.xml");
        assertThat(layout.javaSourceRoots()).containsExactly("src/test/java");
        assertThat(layout.featureRoots()).containsExactly("src/test/resources/features");
        assertThat(layout.gluePackages()).containsExactly("com.example.shop.steps", "com.example.shop.hooks");
        assertThat(layout.runners()).containsExactly("src/test/java/com/example/shop/RunCucumberTest.java");
        assertThat(layout.dependencyInjection()).isEqualTo("picocontainer");
        assertThat(layout.libraries())
                .containsEntry("io.cucumber:cucumber-java", "7.18.0")
                .containsEntry("org.seleniumhq.selenium:selenium-java", "4.21.0");
    }

    @Test
    void readsCucumberOptionsGlueAndPropertiesAndGradle(@TempDir Path repo) throws Exception {
        Path runner = repo.resolve("app/src/test/java/a/RunTest.java");
        Files.createDirectories(runner.getParent());
        Files.writeString(runner, """
                package a;
                @RunWith(Cucumber.class)
                @CucumberOptions(features = "classpath:features", glue = {"a.steps", "classpath:a/hooks"})
                public class RunTest {}
                """);
        Path props = repo.resolve("app/src/test/resources/junit-platform.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "cucumber.glue=b.steps, c.steps\n");
        Files.writeString(repo.resolve("app/build.gradle"),
                "dependencies { testImplementation 'io.cucumber:cucumber-spring:7.15.0' }\n");
        // a Java package called "build" must not be mistaken for build output
        Path inBuildPackage = repo.resolve("app/src/test/java/a/build/Steps.java");
        Files.createDirectories(inBuildPackage.getParent());
        Files.writeString(inBuildPackage, "package a.build; class Steps {}");

        ProjectLayout layout = detector.detect(repo);

        assertThat(layout.buildTool()).isEqualTo("gradle");
        assertThat(layout.gluePackages()).containsExactly("a.steps", "a.hooks", "b.steps", "c.steps");
        assertThat(layout.dependencyInjection()).isEqualTo("spring");
        assertThat(layout.javaSourceRoots()).containsExactly("app/src/test/java");
    }

    @Test
    void minimalRootsDropsNestedDirectories() {
        assertThat(ProjectLayoutDetector.minimalRoots(List.of("a", "a/b", "ab", "c/d", "c/d/e")))
                .containsExactly("a", "ab", "c/d");
    }
}

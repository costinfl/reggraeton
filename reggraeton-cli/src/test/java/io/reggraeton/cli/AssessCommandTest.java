package io.reggraeton.cli;

import io.reggraeton.model.Inventory;
import io.reggraeton.report.Traceability;
import io.reggraeton.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssessCommandTest {

    private static Path legacyShop() {
        return Path.of(System.getProperty("reggraeton.samples", "../samples")).resolve("legacy-shop");
    }

    @Test
    void writesAllReportsForTheSampleRepository(@TempDir Path out) throws Exception {
        int exit = Main.execute("assess", legacyShop().toString(), "--out", out.toString());

        assertThat(exit).isZero();
        assertThat(out.resolve("report.html")).exists();
        assertThat(out.resolve("report.md")).exists();
        assertThat(out.resolve("callgraph.dot")).exists();
        assertThat(out.resolve("callgraph.json")).exists();

        Inventory inventory = Json.read(out.resolve("inventory.json"), Inventory.class);
        assertThat(inventory.testCases()).hasSize(10);

        List<String> csv = Files.readAllLines(out.resolve("traceability.csv"));
        assertThat(csv.get(0)).isEqualTo(String.join(",", Traceability.COLUMNS));
        assertThat(csv).hasSize(1 + inventory.testCases().size());
        assertThat(csv).anySatisfy(line -> assertThat(line)
                .startsWith("src/test/resources/features/login.feature:11,")
                .contains("regression.features.LoginTest#successfulLogin"));
        assertThat(csv).anySatisfy(line -> assertThat(line)
                .contains("regression.features.shop.ProductSearchTest#searchSuggestions")
                .contains(",blocked,"));
        assertThat(csv).anySatisfy(line -> assertThat(line)
                .contains("regression.features.LoginTest#rejectedLogin[3]"));

        String dot = Files.readString(out.resolve("callgraph.dot"));
        assertThat(dot).startsWith("// reggraeton").contains("digraph reggraeton")
                .contains("\"com.example.shop.pages.LoginPage#loginAs(String,String)\"");

        String html = Files.readString(out.resolve("report.html"));
        assertThat(html).contains("reggraeton assessment").contains("Search suggestions").doesNotContain("<script src");
    }

    @Test
    void failOnBlockedReturnsExitCodeTwo(@TempDir Path out) {
        int exit = Main.execute("assess", legacyShop().toString(), "--out", out.toString(), "--fail-on", "blocked");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void reportsUsageErrors() {
        assertThat(Main.execute("assess")).isEqualTo(2);
        assertThat(Main.execute("--version")).isZero();
    }
}

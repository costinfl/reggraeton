package io.reggraeton.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConvertCommandTest {

    private static Path legacyShop() {
        return Path.of(System.getProperty("reggraeton.samples", "../samples")).resolve("legacy-shop");
    }

    @Test
    void convertsInBothModes(@TempDir Path dir) {
        assertThat(Main.execute("convert", legacyShop().toString(), "--out", dir.resolve("bdd").toString(), "--mode", "bdd")).isZero();
        assertThat(Main.execute("convert", legacyShop().toString(), "--out", dir.resolve("native").toString(), "--mode", "native",
                "--base-package", "com.acme.regression")).isZero();

        assertThat(dir.resolve("bdd/src/test/java/regression/RunRegressionTest.java")).exists();
        assertThat(dir.resolve("native/src/test/java/com/acme/regression/features/LoginTest.java")).exists();
        assertThat(dir.resolve("native/src/test/java/com/acme/regression/support/RunConfig.java")).exists();
    }

    @Test
    void refusesNonEmptyOutputWithoutForceAndUnknownModes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("keep.txt"), "x");

        assertThat(Main.execute("convert", legacyShop().toString(), "--out", dir.toString())).isEqualTo(2);
        assertThat(Main.execute("convert", legacyShop().toString(), "--out", dir.resolve("x").toString(), "--mode", "magic")).isEqualTo(2);
        assertThat(Main.execute("convert", legacyShop().toString(), "--out", dir.toString(), "--force")).isZero();
        assertThat(dir.resolve("keep.txt")).exists();
    }
}

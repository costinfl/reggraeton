package io.reggraeton.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtherCommandsTest {

    @Test
    void rulesListAutomaticAndManualApis() {
        String md = RulesCommand.markdown();

        assertThat(md).contains("## Translated automatically").contains("`WebDriver.get` | page.navigate(url)")
                .contains("## Manual conversion").contains("`WebDriver.Options.logs`");
    }

    @Test
    void doctorKnowsWhereEdgeLivesOnEachOs() {
        assertThat(DoctorCommand.candidates("windows 11")).extracting(DoctorCommand.Candidate::channel).contains("msedge");
        // compare Paths, not strings: the test may run on another OS than the one asked for,
        // and Path renders separators for the OS it runs on
        assertThat(DoctorCommand.candidates("mac os x")).extracting(DoctorCommand.Candidate::path)
                .contains(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
        assertThat(DoctorCommand.candidates("linux")).extracting(DoctorCommand.Candidate::path)
                .contains(Path.of("/opt/microsoft/msedge/msedge"));
        // exit code depends on the machine; it must run without launching or downloading anything
        assertThat(Main.execute("doctor", "--channel", "chromium")).isIn(List.of(0, 1));
    }

    @Test
    void compareGatesOnRegressions(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        writeReport(dir.resolve("old"), "<testcase name=\"login()\" classname=\"Login\"/>");
        writeReport(dir.resolve("new"), "<testcase name=\"login()\" classname=\"Login\"><failure message=\"x\"/></testcase>");
        Path out = dir.resolve("cmp");

        assertThat(Main.execute("compare", dir.resolve("old").toString(), dir.resolve("new").toString(),
                "--out", out.toString(), "--label-a", "java11", "--label-b", "java17")).isZero();
        assertThat(out.resolve("compare.html")).exists();
        assertThat(Main.execute("compare", dir.resolve("old").toString(), dir.resolve("new").toString(),
                "--out", out.toString(), "--fail-on", "regression")).isEqualTo(2);
        assertThat(Main.execute("compare", dir.resolve("old").toString(), dir.resolve("old").toString(),
                "--out", out.toString(), "--fail-on", "difference")).isZero();
        assertThat(Main.execute("compare", dir.resolve("old").toString(), dir.resolve("missing").toString(),
                "--out", out.toString())).isNotZero();
    }

    private static void writeReport(Path dir, String testcase) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("TEST-Login.xml"),
                "<?xml version=\"1.0\"?><testsuite name=\"Login\">" + testcase + "</testsuite>");
    }
}

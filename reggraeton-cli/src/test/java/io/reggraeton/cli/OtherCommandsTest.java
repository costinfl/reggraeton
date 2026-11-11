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
        assertThat(DoctorCommand.candidates("mac os x")).extracting(c -> c.path().toString())
                .contains("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        assertThat(DoctorCommand.candidates("linux")).extracting(DoctorCommand.Candidate::path)
                .contains(Path.of("/opt/microsoft/msedge/msedge"));
        // exit code depends on the machine; it must run without launching or downloading anything
        assertThat(Main.execute("doctor", "--channel", "chromium")).isIn(List.of(0, 1));
    }
}

package dev.haris.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A second launch may only ever focus the HUD's own browser, never a window of some other program. */
class HudWindowTest {

    @Test
    void aProcessThatIsNotEdgeOrChromeIsNeverTrustedEvenWithTheRightStartTime() {
        ProcessHandle me = ProcessHandle.current();   // java, not a browser
        long start = me.info().startInstant().orElseThrow().toEpochMilli();

        assertThat(HudWindow.isBrowser(me)).isFalse();
        assertThat(HudWindow.sameBrowserProcess(me, start)).isFalse();
    }

    @Test
    void aReusedProcessNumberWithAnotherStartTimeIsNotTrusted() {
        ProcessHandle me = ProcessHandle.current();
        long start = me.info().startInstant().orElseThrow().toEpochMilli();

        assertThat(HudWindow.sameBrowserProcess(me, start + 1)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aProfileNoBrowserRunsHasNoBrowserProcess(@TempDir Path profile) {
        assertThat(HudWindow.profileBrowserPid(profile)).isEqualTo(-1);
    }

    @Test
    void theProfileIsTheHudsOwnFolder() {
        assertThat(HudWindow.profile().getFileName().toString()).isEqualTo("hud-profile");
        assertThat(HudWindow.profile().getParent().getFileName().toString()).isEqualTo(".haris");
    }
}

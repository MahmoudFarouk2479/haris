package dev.haris.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
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

    /**
     * Stands in for Chromium: a message-only window of its class, named after a profile folder, as the
     * browser process of that profile keeps one. Finding it must give this process.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theOwnerOfAProfileIsFoundThroughItsMessageWindow(@TempDir Path profile) {
        WinDef.HINSTANCE module = Kernel32.INSTANCE.GetModuleHandle(null);
        WinUser.WindowProc proc = (hwnd, msg, wParam, lParam) -> User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam);
        WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
        wc.hInstance = module;
        wc.lpszClassName = HudWindow.MESSAGE_CLASS;
        wc.lpfnWndProc = proc;
        User32.INSTANCE.RegisterClassEx(wc);
        WinDef.HWND window = User32.INSTANCE.CreateWindowEx(0, HudWindow.MESSAGE_CLASS, profile.toString(), 0,
                0, 0, 0, 0, HudWindow.HWND_MESSAGE, null, module, null);
        try {
            assertThat(window).isNotNull();
            assertThat(HudWindow.profileOwnerPid(profile)).isEqualTo(ProcessHandle.current().pid());
            assertThat(HudWindow.profileBrowserPid(profile)).as("this process is not Edge or Chrome").isEqualTo(-1);
            assertThat(HudWindow.profileOwnerPid(profile.resolve("other"))).isEqualTo(-1);
        } finally {
            User32.INSTANCE.DestroyWindow(window);
            User32.INSTANCE.UnregisterClass(HudWindow.MESSAGE_CLASS, module);
        }
    }

    @Test
    void theProfileIsTheHudsOwnFolder() {
        assertThat(HudWindow.profile().getFileName().toString()).isEqualTo("hud-profile");
        assertThat(HudWindow.profile().getParent().getFileName().toString()).isEqualTo(".haris");
    }
}

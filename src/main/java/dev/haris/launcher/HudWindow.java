package dev.haris.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import org.springframework.stereotype.Component;

/**
 * Knows the HUD's own browser window, so a screen capture can step it aside for a moment:
 * "what is on my screen" should show the user's screen, not Haris looking at itself.
 */
@Component
public class HudWindow {

    private static final int SW_MINIMIZE = 6;
    private static final int SW_RESTORE = 9;
    private static final int SW_SHOWMINIMIZED = 2;
    private static final Set<String> BROWSERS = Set.of("msedge.exe", "chrome.exe");

    private volatile long pid = -1;

    void setProcess(Process process) {
        this.pid = process.pid();
    }

    /** Runs {@code action} with the HUD window minimised, then brings it back. */
    public <T> T whileHidden(Callable<T> action) throws Exception {
        List<HWND> windows = isWindows() && pid > 0 ? visibleWindows(pid) : List.of();
        try {
            for (HWND w : windows) {
                User32.INSTANCE.ShowWindow(w, SW_MINIMIZE);
            }
            if (!windows.isEmpty()) {
                Thread.sleep(450);   // let the minimise animation finish
            }
            return action.call();
        } finally {
            for (HWND w : windows) {
                User32.INSTANCE.ShowWindow(w, SW_RESTORE);
                User32.INSTANCE.SetForegroundWindow(w);
            }
        }
    }

    /**
     * A second launch of Haris calls this with the running HUD's browser process: its windows are
     * restored if minimised and brought to the front. Only a running Edge or Chrome process qualifies.
     */
    public static boolean bringToFront(long pid) {
        if (!isWindows() || pid <= 0) {
            return false;
        }
        boolean browser = ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .map(p -> p.info().command().map(c -> BROWSERS.contains(
                        Path.of(c).getFileName().toString().toLowerCase(Locale.ROOT))).orElse(true))
                .orElse(false);
        if (!browser) {
            return false;
        }
        List<HWND> windows = visibleWindows(pid);
        for (HWND w : windows) {
            WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
            if (User32.INSTANCE.GetWindowPlacement(w, placement).booleanValue()
                    && placement.showCmd == SW_SHOWMINIMIZED) {
                User32.INSTANCE.ShowWindow(w, SW_RESTORE);   // only when minimised: a maximised window stays so
            }
            User32.INSTANCE.SetForegroundWindow(w);
        }
        return !windows.isEmpty();
    }

    private static List<HWND> visibleWindows(long pid) {
        List<HWND> found = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
            if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)
                    && User32.INSTANCE.GetWindowTextLength(hwnd) > 0) {
                found.add(hwnd);
            }
            return true;
        }, null);
        return found;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

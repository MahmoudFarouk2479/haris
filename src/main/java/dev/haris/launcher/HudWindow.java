package dev.haris.launcher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import dev.haris.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * Knows the HUD's own browser window, so a screen capture can step it aside for a moment
 * ("what is on my screen" should show the user's screen, not Haris looking at itself), and so a
 * second launch of Haris can bring it to the front.
 * <p>
 * The process Haris starts is not always the one that owns the window: when an Edge process for the
 * HUD's profile already runs, the new one hands the window over and exits. The owner is found the way
 * Chromium finds it itself: the browser process of a profile keeps a hidden message window of class
 * {@code Chrome_MessageWindow} named after the profile folder.
 */
@Component
public class HudWindow {

    private static final int SW_MINIMIZE = 6;
    private static final int SW_RESTORE = 9;
    private static final int SW_SHOWMINIMIZED = 2;
    private static final Set<String> BROWSERS = Set.of("msedge.exe", "chrome.exe");
    /** Parent of message-only windows. Not Pointer.createConstant: that one reaches Windows as an invalid handle. */
    static final HWND HWND_MESSAGE = new HWND(new Pointer(-3));
    static final String MESSAGE_CLASS = "Chrome_MessageWindow";

    private volatile long pid = -1;

    void setProcess(Process process) {
        this.pid = process.pid();
    }

    /** The HUD's private browser profile; its folder name identifies the HUD's browser process. */
    public static Path profile() {
        return SettingsService.dataDir().resolve("hud-profile");
    }

    /** Runs {@code action} with the HUD window minimised, then brings it back. */
    public <T> T whileHidden(Callable<T> action) throws Exception {
        long owner = isWindows() ? profileBrowserPid(profile()) : -1;
        if (owner <= 0) {
            owner = pid;
        }
        List<HWND> windows = isWindows() && owner > 0 ? visibleWindows(owner) : List.of();
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
     * A second launch of Haris calls this: the HUD's windows are restored if minimised and brought to
     * the front. The HUD's browser is found by its profile; the process the running Haris recorded
     * ({@code recordedPid}, started at {@code recordedStartMillis}) is used only if that finds nothing,
     * and only while it is that same Edge or Chrome process, so a reused process number never makes
     * Haris focus another program's window. True if a window was brought forward.
     */
    public static boolean bringToFront(long recordedPid, long recordedStartMillis) {
        if (!isWindows()) {
            return false;
        }
        long owner = profileBrowserPid(profile());
        if (owner <= 0 && ProcessHandle.of(recordedPid).map(p -> sameBrowserProcess(p, recordedStartMillis)).orElse(false)) {
            owner = recordedPid;
        }
        if (owner <= 0) {
            return false;
        }
        List<HWND> windows = visibleWindows(owner);
        // Minimised windows are restored (a maximised one stays so) and focused last, so when the profile
        // also has another window open, the one the user minimised is the one that ends up in front.
        List<HWND> restored = new ArrayList<>();
        for (HWND w : windows) {
            WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
            if (User32.INSTANCE.GetWindowPlacement(w, placement).booleanValue()
                    && placement.showCmd == SW_SHOWMINIMIZED) {
                User32.INSTANCE.ShowWindow(w, SW_RESTORE);
                restored.add(w);
            } else {
                User32.INSTANCE.SetForegroundWindow(w);
            }
        }
        for (HWND w : restored) {
            User32.INSTANCE.SetForegroundWindow(w);
        }
        return !windows.isEmpty();
    }

    /** The Edge or Chrome process running {@code profile}, or -1. */
    static long profileBrowserPid(Path profile) {
        long found = profileOwnerPid(profile);
        return found > 0 && ProcessHandle.of(found).filter(ProcessHandle::isAlive).map(HudWindow::isBrowser).orElse(false)
                ? found : -1;
    }

    /** The process owning the {@code Chrome_MessageWindow} named after {@code profile}, or -1. */
    static long profileOwnerPid(Path profile) {
        HWND message = User32.INSTANCE.FindWindowEx(HWND_MESSAGE, null, MESSAGE_CLASS, profile.toString());
        if (message == null) {
            return -1;
        }
        IntByReference owner = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(message, owner);
        return owner.getValue() > 0 ? owner.getValue() : -1;
    }

    /** Alive, started when recorded (to the millisecond) and an Edge or Chrome executable. Unknown means no. */
    static boolean sameBrowserProcess(ProcessHandle p, long startMillis) {
        Optional<Instant> start = p.info().startInstant();
        return p.isAlive() && start.isPresent() && start.get().toEpochMilli() == startMillis && isBrowser(p);
    }

    static boolean isBrowser(ProcessHandle p) {
        return p.info().command()
                .map(c -> BROWSERS.contains(Path.of(c).getFileName().toString().toLowerCase(Locale.ROOT)))
                .orElse(false);
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

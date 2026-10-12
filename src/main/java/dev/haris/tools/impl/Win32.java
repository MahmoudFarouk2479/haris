package dev.haris.tools.impl;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/** The handful of Win32 calls the tools need, bound directly — no shell, no helper programs. */
final class Win32 {

    private Win32() {}

    static final byte VK_VOLUME_MUTE = (byte) 0xAD;
    static final byte VK_VOLUME_DOWN = (byte) 0xAE;
    static final byte VK_VOLUME_UP = (byte) 0xAF;
    static final byte VK_MEDIA_NEXT_TRACK = (byte) 0xB0;
    static final byte VK_MEDIA_PREV_TRACK = (byte) 0xB1;
    static final byte VK_MEDIA_PLAY_PAUSE = (byte) 0xB3;

    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        void keybd_event(byte vk, byte scan, int flags, Pointer extraInfo);

        boolean LockWorkStation();
    }

    interface PowrProf extends StdCallLibrary {
        PowrProf INSTANCE = Native.load("powrprof", PowrProf.class);

        boolean SetSuspendState(boolean hibernate, boolean forceCritical, boolean disableWakeEvent);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static void press(byte vk, int times) throws InterruptedException {
        for (int i = 0; i < times; i++) {
            User32.INSTANCE.keybd_event(vk, (byte) 0, KEYEVENTF_EXTENDEDKEY, null);
            User32.INSTANCE.keybd_event(vk, (byte) 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, null);
            if (times > 1) {
                Thread.sleep(15);
            }
        }
    }
}

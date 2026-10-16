package dev.haris.launcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * One Haris per user. The running Haris holds an OS lock on {@code ~/.haris/instance.lock}; the OS
 * drops it when the process ends, also after a crash or a kill, so a leftover file never blocks the
 * next start. A second launch starts no core: it brings the running Haris window to the front and
 * exits. Nothing listens on a port for this and no token is involved: the second launch only reads
 * {@code ~/.haris/instance}, where the running Haris notes its window's browser process ({@code
 * window <pid>}) or that it is shutting down ({@code closing}). While it shuts down (engines stopping),
 * a new launch waits for it to finish and then starts normally, so "close and reopen" still works.
 */
public final class SingleInstance {

    static final String LOCK_FILE = "instance.lock";
    static final String STATE_FILE = "instance";
    private static final String WINDOW = "window ";
    private static final String CLOSING = "closing";
    private static final long POLL_MS = 250;

    /** The lock this process holds, kept reachable so it is never released before exit. */
    private static volatile SingleInstance current;

    /**
     * Brings the running HUD window to the front; true if there was one. Gets the browser process the
     * running Haris started (its id and start time), which may since have handed the window to another.
     */
    @FunctionalInterface
    public interface Activator {
        boolean bringToFront(long pid, long startMillis);
    }

    /** What the running Haris noted about its window: the browser process it started. */
    record WindowRef(long pid, long startMillis) {
    }

    private final Path dir;
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(Path dir, FileChannel channel, FileLock lock) {
        this.dir = dir;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Decides whether this launch runs. Returns the held instance when it should (no other Haris, or
     * the other one finished shutting down within {@code closeWait}); returns empty when another Haris
     * runs: its window was brought to the front, or none appeared within {@code windowWait}.
     */
    public static Optional<SingleInstance> claim(Path dir, Activator activator, Duration windowWait, Duration closeWait)
            throws IOException {
        long start = System.nanoTime();
        for (;;) {
            Optional<SingleInstance> mine = tryAcquire(dir);
            if (mine.isPresent()) {
                current = mine.get();
                return mine;
            }
            String state = readState(dir);
            long waited = (System.nanoTime() - start) / 1_000_000;
            if (CLOSING.equals(state)) {
                if (waited >= closeWait.toMillis()) {
                    note(dir, "Haris is still shutting down; this launch exited. Start Haris again in a moment.");
                    return Optional.empty();
                }
            } else {
                WindowRef window = windowRef(state);
                // No window noted yet: Haris is still starting; keep asking until it opens one.
                if (window != null && activator.bringToFront(window.pid(), window.startMillis())) {
                    note(dir, "Haris is already running; this second launch brought its window to the front and exited.");
                    return Optional.empty();
                }
                if (waited >= windowWait.toMillis()) {
                    note(dir, "Haris is already running (its window was not found); this second launch exited.");
                    return Optional.empty();
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** Takes the lock if no other Haris holds it, and forgets what an earlier run noted. */
    static Optional<SingleInstance> tryAcquire(Path dir) throws IOException {
        Files.createDirectories(dir);
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            forgetState(dir);   // from a run that ended (or crashed) earlier
            return Optional.of(new SingleInstance(dir, channel, lock));
        } catch (OverlappingFileLockException e) {
            channel.close();   // held by this same JVM (tests)
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Best effort: a state file that cannot be removed must never cost this launch its lock. */
    private static void forgetState(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(STATE_FILE));
        } catch (IOException | RuntimeException e) {
            try {
                Files.writeString(dir.resolve(STATE_FILE), "", StandardCharsets.US_ASCII);
            } catch (IOException | RuntimeException ignored) {
                // a second launch then finds no window it can trust and gives up on its own
            }
        }
    }

    /**
     * The HUD window was opened by this browser process (only recorded by the Haris that holds the
     * lock). Its start time is kept too, so a process number reused by another program never counts.
     */
    public static void windowOpened(long pid, long startMillis) {
        SingleInstance me = current;
        if (me != null) {
            me.writeState(WINDOW + pid + " " + startMillis);
        }
    }

    /** This Haris is shutting down: a new launch should wait for it instead of looking for its window. */
    public static void closing() {
        SingleInstance me = current;
        if (me != null) {
            me.writeState(CLOSING);
        }
    }

    /** Releases the lock (normally the OS does this when the process exits). */
    void release() throws IOException {
        if (current == this) {
            current = null;
        }
        lock.release();
        channel.close();
    }

    private void writeState(String state) {
        try {
            Files.writeString(dir.resolve(STATE_FILE), state, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            // Only a nicety for a second launch; never a reason to fail.
        }
    }

    static String readState(Path dir) {
        try {
            return Files.readString(dir.resolve(STATE_FILE), StandardCharsets.US_ASCII).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** {@code window <pid> <startMillis>}, or null for anything else. */
    static WindowRef windowRef(String state) {
        if (state == null || !state.startsWith(WINDOW)) {
            return null;
        }
        String[] parts = state.substring(WINDOW.length()).strip().split(" ");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new WindowRef(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One line in the app log, since a second launch has no console and no Spring logging. */
    static void note(Path dir, String message) {
        String line = OffsetDateTime.now() + "  INFO " + ProcessHandle.current().pid()
                + " --- [second launch] dev.haris.launcher.SingleInstance : " + message + System.lineSeparator();
        try {
            Path log = dir.resolve("logs").resolve("haris.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // nowhere else to say it
        }
    }
}

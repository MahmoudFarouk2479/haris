package dev.haris.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One Haris per user, without a stale lock ever blocking the next start. */
class SingleInstanceTest {

    @TempDir
    Path dir;

    private final List<SingleInstance> held = new ArrayList<>();
    private final List<Long> activated = new ArrayList<>();

    @AfterEach
    void releaseAll() throws IOException {
        for (SingleInstance i : held) {
            i.release();
        }
    }

    private Optional<SingleInstance> claim(Duration windowWait, Duration closeWait) throws IOException {
        Optional<SingleInstance> got = SingleInstance.claim(dir, pid -> {
            activated.add(pid);
            return true;
        }, windowWait, closeWait);
        got.ifPresent(held::add);
        return got;
    }

    @Test
    void theFirstLaunchRuns() throws IOException {
        assertThat(claim(Duration.ZERO, Duration.ZERO)).isPresent();
        assertThat(activated).isEmpty();
    }

    @Test
    void aSecondLaunchBringsTheRunningWindowForwardAndDoesNotRun() throws IOException {
        assertThat(claim(Duration.ZERO, Duration.ZERO)).isPresent();
        SingleInstance.windowOpened(4242);

        assertThat(claim(Duration.ofSeconds(5), Duration.ZERO)).isEmpty();
        assertThat(activated).containsExactly(4242L);
        assertThat(Files.readString(dir.resolve("logs/haris.log"))).contains("already running");
    }

    @Test
    void aSecondLaunchWithNoWindowToFindGivesUpQuietly() throws IOException {
        SingleInstance first = SingleInstance.tryAcquire(dir).orElseThrow();
        held.add(first);   // started, but its window is not open yet (and never opens here)

        long t0 = System.nanoTime();
        assertThat(claim(Duration.ofMillis(600), Duration.ZERO)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(600));
        assertThat(activated).isEmpty();
        assertThat(Files.readString(dir.resolve("logs/haris.log"))).contains("window was not found");
    }

    @Test
    void aLaunchDuringShutdownWaitsForItAndThenRuns() throws Exception {
        SingleInstance first = claim(Duration.ZERO, Duration.ZERO).orElseThrow();
        SingleInstance.windowOpened(77);
        SingleInstance.closing();
        held.remove(first);
        Thread exit = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(700);
                first.release();   // the old core finishes exiting
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(claim(Duration.ZERO, Duration.ofSeconds(10))).isPresent();
        exit.join();
        assertThat(activated).isEmpty();
        assertThat(SingleInstance.readState(dir)).as("the new run starts with a clean state").isEmpty();
    }

    @Test
    void aLeftoverLockFileAndStateFromACrashDoNotBlock() throws IOException {
        Files.writeString(dir.resolve(SingleInstance.LOCK_FILE), "");
        Files.writeString(dir.resolve(SingleInstance.STATE_FILE), "window 999999");

        assertThat(claim(Duration.ZERO, Duration.ZERO)).isPresent();
        assertThat(activated).isEmpty();
        assertThat(SingleInstance.readState(dir)).isEmpty();
    }

    @Test
    void aLockHeldByAnotherProcessBlocksUntilThatProcessDies() throws Exception {
        Process other = holdLockInAnotherJvm();
        try {
            assertThat(SingleInstance.tryAcquire(dir)).isEmpty();
        } finally {
            other.destroy();   // like a crash: the process never releases the lock itself
            other.waitFor(10, TimeUnit.SECONDS);
        }
        Optional<SingleInstance> next = SingleInstance.tryAcquire(dir);
        next.ifPresent(held::add);
        assertThat(next).as("the OS released the dead process's lock").isPresent();
    }

    @Test
    void windowPidsAreParsedStrictly() {
        assertThat(SingleInstance.windowPid("window 1234")).isEqualTo(1234);
        assertThat(SingleInstance.windowPid("closing")).isEqualTo(-1);
        assertThat(SingleInstance.windowPid("window x")).isEqualTo(-1);
        assertThat(SingleInstance.windowPid("")).isEqualTo(-1);
    }

    /** Starts a JVM that takes the lock in {@link #dir} and then waits; returns once it holds it. */
    private Process holdLockInAnotherJvm() throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), dir.toString()).redirectErrorStream(true).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        assertThat(out.readLine()).isEqualTo("locked");
        return p;
    }

    /** Runs in the other JVM. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            Optional<SingleInstance> lock = SingleInstance.tryAcquire(Path.of(args[0]));
            System.out.println(lock.isPresent() ? "locked" : "busy");
            System.out.flush();
            Thread.sleep(60_000);
        }
    }
}

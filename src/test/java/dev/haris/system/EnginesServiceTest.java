package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Docker Desktop can take a minute to answer after a restart; Haris must not give up at once. */
class EnginesServiceTest {

    @Test
    void waitsUntilDockerAnswers() {
        AtomicInteger calls = new AtomicInteger();
        boolean ready = EnginesService.waitFor(() -> calls.incrementAndGet() >= 3, 10, 1);
        assertThat(ready).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        AtomicInteger calls = new AtomicInteger();
        boolean ready = EnginesService.waitFor(() -> {
            calls.incrementAndGet();
            return false;
        }, 4, 1);
        assertThat(ready).isFalse();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aCommandThatHangsIsEndedAtItsTimeout() {
        long start = System.currentTimeMillis();
        // ping -n 30 takes ~29 s; a wedged nvidia-smi or docker must not hold start-up that long.
        EnginesService.Run r = EnginesService.run(List.of("ping", "-n", "30", "127.0.0.1"), 500);
        assertThat(r.exit()).isEqualTo(-1);
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
    }

    @Test
    void aCommandThatCannotStartIsAFailureNotAnException() {
        assertThat(EnginesService.run(List.of("no-such-command-haris-test"), 5_000).exit()).isEqualTo(-1);
    }

    @Test
    void answersAtOnceWhenDockerIsAlreadyUp() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(EnginesService.waitFor(() -> calls.incrementAndGet() > 0, 10, 60_000)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    // ── the background GPU image download never outlives Haris ──

    /** Stands in for a {@code docker pull} that has finished (or is ended by destroy). */
    static final class FakePull extends Process {
        volatile boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static EnginesService service() {
        return new EnginesService(null, null, "", false, false);
    }

    @Test
    void aStopBetweenThePullCheckAndItsStartStillEndsThePull(@TempDir Path dir) {
        EnginesService engines = service();
        FakePull pull = new FakePull();
        engines.pullStarter = builder -> {
            engines.stop();   // Haris exits while docker pull is being started: stop() sees no process yet
            return pull;
        };
        engines.pullImage(dir.resolve("compose.yaml"), "gpu:image");
        assertThat(pull.destroyed).isTrue();
    }

    @Test
    void aPullThatFinishesIsNotEnded(@TempDir Path dir) {
        EnginesService engines = service();
        FakePull pull = new FakePull();
        engines.pullStarter = builder -> pull;
        engines.pullImage(dir.resolve("compose.yaml"), "gpu:image");
        assertThat(pull.destroyed).isFalse();
    }

    @Test
    void noPullStartsOnceHarisIsStopping(@TempDir Path dir) {
        EnginesService engines = service();
        AtomicInteger starts = new AtomicInteger();
        engines.pullStarter = builder -> {
            starts.incrementAndGet();
            return new FakePull();
        };
        engines.stop();
        engines.pullImage(dir.resolve("compose.yaml"), "gpu:image");
        assertThat(starts.get()).isZero();
    }
}

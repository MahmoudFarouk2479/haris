package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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
}

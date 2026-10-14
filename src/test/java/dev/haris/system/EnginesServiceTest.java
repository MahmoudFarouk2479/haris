package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
    void answersAtOnceWhenDockerIsAlreadyUp() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(EnginesService.waitFor(() -> calls.incrementAndGet() > 0, 10, 60_000)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }
}

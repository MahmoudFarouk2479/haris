package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The full status report is given once per calendar day; later launches get the short greeting. */
class GreetingControllerTest {

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("Africa/Cairo"));
    }

    @Test
    void fullOnceThenShortTheSameDay(@TempDir Path dir) {
        Path state = dir.resolve("last-greeting");
        assertThat(new GreetingController(state, at("2026-10-14T05:00:00Z")).claim()).containsEntry("full", true);
        assertThat(new GreetingController(state, at("2026-10-14T19:00:00Z")).claim()).containsEntry("full", false);
        assertThat(new GreetingController(state, at("2026-10-14T20:30:00Z")).claim()).containsEntry("full", false);
    }

    @Test
    void aNewDayIsFullAgain(@TempDir Path dir) {
        Path state = dir.resolve("last-greeting");
        new GreetingController(state, at("2026-10-14T05:00:00Z")).claim();
        // 22:30 UTC is already the 15th in Cairo: the local calendar day counts.
        assertThat(new GreetingController(state, at("2026-10-14T22:30:00Z")).claim()).containsEntry("full", true);
    }

    @Test
    void createsTheDataFolderWhenMissing(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("nested").resolve("last-greeting");
        assertThat(new GreetingController(state, at("2026-10-14T05:00:00Z")).claim()).containsEntry("full", true);
        assertThat(Files.readString(state).strip()).isEqualTo("2026-10-14");
    }
}

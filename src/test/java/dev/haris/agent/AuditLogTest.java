package dev.haris.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import dev.haris.tools.Risk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** Logs written before the F7 fix still hold private text; the first start after it removes it. */
class AuditLogTest {

    @TempDir
    Path dir;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void scrubsPrivateTextFromOlderEntriesOnce() throws Exception {
        Path file = dir.resolve("audit.jsonl");
        Files.write(file, List.of(
                line("clipboard_write", "Copy to clipboard: \"hunter2\""),
                line("compose_message", "Draft email message to a@b.example: \"the secret plan\""),
                line("remember", "Remember: \"Lives at 5 Nile St\""),
                line("forget", "Forget: \"Wife's name is Mona\""),
                line("read_file", "Read file C:\\Users\\me\\notes.txt"),
                line("undo", "forget \"My PIN hint is Zamalek\""),
                line("undo", "remember \"Sister lives in Alexandria\" again"),
                line("undo", "delete C:\\Users\\me\\draft.txt"),
                "not json at all"));

        new AuditLog(json, dir);

        String after = Files.readString(file);
        assertThat(after).doesNotContain("hunter2", "secret plan", "Nile St", "Mona", "Zamalek", "Alexandria");
        assertThat(after).contains("(redacted)", "Read file", "not json at all", "delete C:");
        assertThat(dir.resolve(AuditLog.SCRUB_MARKER)).exists();

        Files.writeString(file, line("clipboard_write", "written after the scrub") + System.lineSeparator(),
                StandardOpenOption.APPEND);
        new AuditLog(json, dir);
        assertThat(Files.readString(file)).contains("written after the scrub");
    }

    @Test
    void aFreshInstallStartsCleanlyAndRecordsToTheGivenDirectory() throws Exception {
        AuditLog log = new AuditLog(json, dir);
        assertThat(dir.resolve(AuditLog.SCRUB_MARKER)).exists();

        log.record("system_status", "Read system status", Risk.SAFE, "auto", "ok");
        assertThat(Files.readString(dir.resolve("audit.jsonl"))).contains("Read system status");
    }

    private String line(String tool, String summary) {
        return json.writeValueAsString(new AuditLog.Entry("2026-10-01T10:00:00Z", tool, summary, Risk.CONFIRM, "approved", "ok"));
    }
}

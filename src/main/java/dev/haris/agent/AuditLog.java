package dev.haris.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import dev.haris.settings.SettingsService;
import dev.haris.tools.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only record of every action the assistant attempted, approved or not, in
 * ~/.haris/audit.jsonl. Arguments are summarised, never dumped, so file contents and
 * clipboard text are not copied into the log.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int RECENT = 50;

    public record Entry(String time, String tool, String summary, Risk risk, String decision, String outcome) {}

    private final JsonMapper json;
    private final Path file = SettingsService.dataDir().resolve("audit.jsonl");
    private final Deque<Entry> recent = new ArrayDeque<>();

    public AuditLog(JsonMapper json) {
        this.json = json;
    }

    public synchronized void record(String tool, String summary, Risk risk, String decision, String outcome) {
        Entry e = new Entry(Instant.now().toString(), tool, summary, risk, decision, outcome);
        recent.addFirst(e);
        while (recent.size() > RECENT) {
            recent.removeLast();
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(e) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.error("Could not write audit log: {}", ex.getMessage());
        }
    }

    public synchronized List<Entry> recent() {
        return new ArrayList<>(recent);
    }
}

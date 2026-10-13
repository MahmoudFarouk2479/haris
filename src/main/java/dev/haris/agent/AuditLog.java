package dev.haris.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import dev.haris.settings.SettingsService;
import dev.haris.tools.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Append-only record of every action the assistant attempted, approved or not, in
 * ~/.haris/audit.jsonl. Each entry keeps the tool's audit summary, which never holds argument
 * content — clipboard text, message bodies and memory facts stay out of the file.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int RECENT = 50;

    /** Present once entries written before content-free summaries have been scrubbed. */
    static final String SCRUB_MARKER = "audit.redacted-v1";
    /** Tools whose older summaries quoted private text. */
    private static final Set<String> CONTENT_TOOLS = Set.of("clipboard_write", "compose_message", "remember", "forget");
    /** Older undo entries described undoing a memory change by quoting the fact itself. */
    private static final Pattern QUOTED_MEMORY_UNDO = Pattern.compile("^(forget|remember) \".*", Pattern.DOTALL);

    public record Entry(String time, String tool, String summary, Risk risk, String decision, String outcome) {}

    private final JsonMapper json;
    private final Path file;
    private final Deque<Entry> recent = new ArrayDeque<>();

    @Autowired
    public AuditLog(JsonMapper json) {
        this(json, SettingsService.dataDir());
    }

    AuditLog(JsonMapper json, Path dataDir) {
        this.json = json;
        this.file = dataDir.resolve("audit.jsonl");
        scrubOnce(dataDir.resolve(SCRUB_MARKER));
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

    /** Rewrites older entries of CONTENT_TOOLS with a redacted summary, once per installation. */
    private void scrubOnce(Path marker) {
        if (Files.exists(marker)) {
            return;
        }
        try {
            if (Files.exists(file)) {
                List<String> out = new ArrayList<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    out.add(redact(line));
                }
                Path tmp = file.resolveSibling("audit.jsonl.tmp");
                Files.write(tmp, out, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.createDirectories(marker.getParent());
            Files.createFile(marker);
        } catch (IOException | RuntimeException e) {
            log.error("Could not scrub the audit log; trying again at the next start: {}", e.getMessage());
        }
    }

    private String redact(String line) {
        try {
            JsonNode node = json.readTree(line);
            if (node instanceof ObjectNode entry && (CONTENT_TOOLS.contains(entry.path("tool").asString())
                    || entry.path("tool").asString().equals("undo")
                    && QUOTED_MEMORY_UNDO.matcher(entry.path("summary").asString()).matches())) {
                entry.put("summary", "(redacted)");
                return json.writeValueAsString(entry);
            }
        } catch (RuntimeException notJson) {
            // keep lines that are not JSON exactly as they were
        }
        return line;
    }
}

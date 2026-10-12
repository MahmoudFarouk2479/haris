package dev.haris.memory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import dev.haris.security.SecretStore;
import dev.haris.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/** Spring-facing wrapper: wires the store to ~/.haris/memory.enc and the vault key. */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int PROMPT_BUDGET_CHARS = 2500;

    private final MemoryStore store;

    public MemoryService(SecretStore secrets, JsonMapper json) {
        this.store = new MemoryStore(SettingsService.dataDir().resolve("memory.enc"),
                () -> MemoryStore.decodeKey(secrets.getOrCreate(SecretStore.MEMORY_KEY)), json);
    }

    public MemoryStore store() {
        return store;
    }

    /**
     * The newest facts that fit in a small budget, for the start of a conversation. Older ones are
     * reachable through the recall tool, so memory can grow without bloating every request.
     */
    public String forPrompt() {
        try {
            List<MemoryStore.Fact> all = store.all();
            if (all.isEmpty()) {
                return "(nothing yet)";
            }
            StringBuilder out = new StringBuilder();
            for (int i = all.size() - 1; i >= 0; i--) {
                MemoryStore.Fact f = all.get(i);
                String line = "- [" + f.category() + "] " + f.text() + "\n";
                if (out.length() + line.length() > PROMPT_BUDGET_CHARS) {
                    out.append("- … ").append(i + 1).append(" older facts: use recall to search them\n");
                    break;
                }
                out.append(line);
            }
            return out.toString().strip();
        } catch (IOException | RuntimeException e) {
            log.warn("Memory unavailable: {}", e.getMessage());
            return "(memory is unavailable: " + e.getMessage() + ")";
        }
    }

    public static String format(List<MemoryStore.Fact> facts) {
        return facts.stream().map(f -> f.id() + "  [" + f.category() + "] " + f.text()).collect(Collectors.joining("\n"));
    }
}

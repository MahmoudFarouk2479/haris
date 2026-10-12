package dev.haris.system;

import dev.haris.llm.ProviderFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import dev.haris.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily briefing, offered once per day on the first launch. It is an ordinary request
 * through the agent, so it uses the same tools under the same permission gate as anything else.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingService {

    static final String PROMPT = """
            Daily briefing, please. Greet me for the time of day and tell me today's date. Then, using your tools:
            the weather in my home city (weather), my reminders for today (reminders, list), and three top news
            headlines (one web_search; use only the result snippets, do not open pages). Finish with anything
            from memory that matters today. Under 120 words, in the language I usually use with you.""";

    private final SettingsService settings;
    private final ProviderFactory providers;
    private final Path stateFile = SettingsService.dataDir().resolve("last-briefing");

    public BriefingService(SettingsService settings, ProviderFactory providers) {
        this.settings = settings;
        this.providers = providers;
    }

    /** Returns the briefing request if one is due today, and marks today as done. */
    @PostMapping("/claim")
    public synchronized ResponseEntity<Map<String, String>> claim() throws IOException {
        // Not before an engine is set up: today's briefing stays due until it can actually run.
        if (!Boolean.TRUE.equals(settings.get().morningBriefing()) || !providers.ready()) {
            return ResponseEntity.noContent().build();
        }
        String today = LocalDate.now().toString();
        if (last().filter(today::equals).isPresent()) {
            return ResponseEntity.noContent().build();
        }
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, today);
        return ResponseEntity.ok(Map.of("prompt", PROMPT));
    }

    private Optional<String> last() {
        try {
            return Optional.of(Files.readString(stateFile).strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

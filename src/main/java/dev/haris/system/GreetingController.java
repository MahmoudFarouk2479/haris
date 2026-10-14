package dev.haris.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import dev.haris.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first launch of each calendar day greets with a full status report (system, engines,
 * weather); later launches that day get the short greeting. Independent of the daily briefing.
 */
@RestController
@RequestMapping("/api/greeting")
public class GreetingController {

    private static final Logger log = LoggerFactory.getLogger(GreetingController.class);

    private final Path stateFile;
    private final Clock clock;

    @Autowired
    public GreetingController() {
        this(SettingsService.dataDir().resolve("last-greeting"), Clock.systemDefaultZone());
    }

    GreetingController(Path stateFile, Clock clock) {
        this.stateFile = stateFile;
        this.clock = clock;
    }

    /** {@code full: true} the first time it is asked each day, then {@code false} until tomorrow. */
    @PostMapping("/claim")
    public synchronized Map<String, Boolean> claim() {
        String today = LocalDate.now(clock).toString();
        if (today.equals(last())) {
            return Map.of("full", false);
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, today);
        } catch (IOException e) {
            log.warn("Could not record today's greeting: {}", e.getMessage());
        }
        return Map.of("full", true);
    }

    private String last() {
        try {
            return Files.readString(stateFile).strip();
        } catch (IOException e) {
            return null;
        }
    }
}

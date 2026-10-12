package dev.haris.system;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only hardware telemetry for the HUD panels. */
@RestController
@RequestMapping("/api")
public class SystemStatsController {

    private final SystemStats stats;

    public SystemStatsController(SystemStats stats) {
        this.stats = stats;
    }

    @GetMapping("/system")
    public Map<String, Object> stats() {
        return stats.snapshot();
    }
}

package dev.haris.tools.impl;

import java.util.Map;

import dev.haris.system.SystemStats;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class SystemStatusTool implements Tool {

    private final SystemStats stats;

    public SystemStatusTool(SystemStats stats) {
        this.stats = stats;
    }

    @Override
    public String name() {
        return "system_status";
    }

    @Override
    public String description() {
        return "Current CPU load, memory, battery, uptime and free disk space of this computer.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.none();
    }

    @Override
    public String summarize(JsonNode input) {
        return "Read system status";
    }

    @Override
    public ToolOutcome run(JsonNode input) {
        Map<String, Object> s = stats.snapshot();
        return ToolOutcome.ok(s + "\ndisks=" + stats.disks());
    }
}

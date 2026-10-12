package dev.haris.memory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Searches long-term memory. Read-only and local; every stored fact was approved by the user. */
@Component
public class RecallTool implements Tool {

    private final MemoryService memory;

    public RecallTool(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String description() {
        return "Search long-term memory for facts about the user beyond those listed in your instructions.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("query"), Schema.str("query", "What to look for, e.g. 'sister birthday'"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Search memory for \"" + Args.str(input, "query", 200) + "\"";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        try {
            List<MemoryStore.Fact> found = memory.store().search(Args.str(input, "query", 200), 15);
            return ToolOutcome.ok(found.isEmpty() ? "Nothing in memory matches." : MemoryService.format(found));
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
    }
}

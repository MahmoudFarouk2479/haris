package dev.haris.memory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class ForgetTool implements Tool {

    private final MemoryService memory;

    public ForgetTool(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "forget";
    }

    @Override
    public String description() {
        return "Delete one fact from long-term memory by its id (find ids with recall).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("id"), Schema.str("id", "Fact id"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String id = Args.str(input, "id", 20);
        try {
            return memory.store().all().stream().filter(f -> f.id().equals(id)).findFirst()
                    .map(f -> "Forget: \"" + f.text() + "\"")
                    .orElseThrow(() -> new ToolException("No memory with id " + id + "."));
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
    }

    @Override
    public String auditSummary(JsonNode input) throws ToolException {
        return "Forget memory " + Args.str(input, "id", 20);
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String id = Args.str(input, "id", 20);
        try {
            MemoryStore.Fact fact = memory.store().all().stream().filter(f -> f.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ToolException("No memory with id " + id + "."));
            memory.store().remove(id);
            return ToolOutcome.undoable("Forgotten.", new ToolOutcome.Undo("remember memory " + id + " again",
                    () -> memory.store().add(fact.text(), fact.category())));
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
    }
}

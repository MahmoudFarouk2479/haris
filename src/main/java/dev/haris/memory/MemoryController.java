package dev.haris.memory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dev.haris.agent.AuditLog;
import dev.haris.tools.Risk;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's view of memory: everything Haris knows, and the power to delete any of it. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memory;
    private final AuditLog audit;

    public MemoryController(MemoryService memory, AuditLog audit) {
        this.memory = memory;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        try {
            List<MemoryStore.Fact> facts = memory.store().all();
            return ResponseEntity.ok(Map.of("facts", facts));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("facts", List.of(), "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) throws IOException {
        boolean removed = memory.store().remove(id);
        audit.record("memory", "user deleted memory " + id, Risk.SAFE, "user", removed ? "ok" : "not found");
        return Map.of("ok", removed);
    }

    @DeleteMapping
    public Map<String, Boolean> deleteAll() throws IOException {
        memory.store().clear();
        audit.record("memory", "user erased all memory", Risk.SAFE, "user", "ok");
        return Map.of("ok", true);
    }
}

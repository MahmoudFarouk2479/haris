package dev.haris.agent;

import java.util.List;
import java.util.Map;

import dev.haris.tools.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The user's side of the permission system: approvals, the activity log and undo. */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final ConfirmationBroker confirmations;
    private final AuditLog audit;
    private final UndoStack undo;
    private final EventHub events;
    private final ToolRegistry tools;
    private final ConversationService conversations;

    public AgentController(ConfirmationBroker confirmations, AuditLog audit, UndoStack undo, EventHub events,
                           ToolRegistry tools, ConversationService conversations) {
        this.confirmations = confirmations;
        this.audit = audit;
        this.undo = undo;
        this.events = events;
        this.tools = tools;
        this.conversations = conversations;
    }

    public record DecisionBody(ConfirmationBroker.Decision decision) {}

    @PostMapping("/confirm/{id}")
    public ResponseEntity<Map<String, Boolean>> confirm(@PathVariable String id, @RequestBody DecisionBody body) {
        if (body.decision() == null) {
            return ResponseEntity.badRequest().build();
        }
        boolean ok = confirmations.resolve(id, body.decision());
        return ok ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(410).body(Map.of("ok", false));
    }

    @GetMapping("/activity")
    public Map<String, Object> activity() {
        return Map.of(
                "entries", audit.recent(),
                "undo", undo.peek().orElse(""),
                "tools", tools.size(),
                "externalContent", conversations.current().hasExternalContent());
    }

    @PostMapping("/undo")
    public Map<String, String> undo() {
        return Map.of("message", undo.undoLast());
    }

    @GetMapping(value = "/events", produces = "text/event-stream")
    public SseEmitter events() {
        return events.subscribe();
    }

    @GetMapping("/tools")
    public List<String> toolNames() {
        return tools.specs().stream().map(s -> s.name()).toList();
    }
}

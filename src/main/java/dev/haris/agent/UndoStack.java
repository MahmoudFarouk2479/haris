package dev.haris.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import dev.haris.tools.Risk;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

/** Reversals for the most recent reversible actions. Triggered only from the HUD's Undo button. */
@Component
public class UndoStack {

    private static final int MAX = 25;

    private final Deque<ToolOutcome.Undo> stack = new ArrayDeque<>();
    private final AuditLog audit;

    public UndoStack(AuditLog audit) {
        this.audit = audit;
    }

    public synchronized void push(ToolOutcome.Undo undo) {
        stack.push(undo);
        while (stack.size() > MAX) {
            stack.removeLast();
        }
    }

    public synchronized Optional<String> peek() {
        return Optional.ofNullable(stack.peek()).map(ToolOutcome.Undo::description);
    }

    public synchronized String undoLast() {
        ToolOutcome.Undo undo = stack.poll();
        if (undo == null) {
            return "Nothing to undo.";
        }
        try {
            undo.action().run();
            audit.record("undo", undo.description(), Risk.SAFE, "user", "ok");
            return "Undone: " + undo.description();
        } catch (Exception e) {
            audit.record("undo", undo.description(), Risk.SAFE, "user", "error: " + e.getMessage());
            return "Could not undo (" + undo.description() + "): " + e.getMessage();
        }
    }
}

package dev.haris.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.haris.llm.Turn;

/**
 * One conversation. The system prompt is frozen when it starts and the history only ever
 * grows (or is cut back to a point inside the current, failed request), which keeps Claude's
 * thinking blocks valid and its prompt cache warm.
 */
public final class Conversation {

    private final String systemPrompt;
    private final List<Turn> turns = new ArrayList<>();
    private final Set<String> sessionApprovals = new HashSet<>();
    private boolean externalContent;

    Conversation(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public synchronized List<Turn> turns() {
        return List.copyOf(turns);
    }

    public synchronized int size() {
        return turns.size();
    }

    public synchronized void append(Turn turn) {
        turns.add(turn);
    }

    /** Drops turns appended by a request that failed, so the history never holds a half-finished exchange. */
    public synchronized void truncate(int size) {
        while (turns.size() > size) {
            turns.removeLast();
        }
    }

    /**
     * Once content from outside (a file, the clipboard, a web page) has entered the context,
     * it may carry planted instructions, so session-wide approvals stop applying for the rest
     * of this conversation: every non-trivial action is confirmed individually.
     */
    public synchronized void markExternalContent() {
        externalContent = true;
    }

    public synchronized boolean hasExternalContent() {
        return externalContent;
    }

    public synchronized void approveForSession(String tool) {
        sessionApprovals.add(tool);
    }

    public synchronized boolean approvedForSession(String tool) {
        return sessionApprovals.contains(tool);
    }
}

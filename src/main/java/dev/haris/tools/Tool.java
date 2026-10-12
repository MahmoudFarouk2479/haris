package dev.haris.tools;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * A typed, allow-listed capability. There is deliberately no tool that runs code, a shell
 * command or an arbitrary program: every action is a fixed operation with validated arguments.
 */
public interface Tool {

    String name();

    /** Shown to the model. Keep it short — small local models read every word on every turn. */
    String description();

    /** JSON-schema object for the arguments. */
    Map<String, Object> schema();

    /** Risk of this particular call (e.g. "lock" vs "shutdown" for the same tool). */
    default Risk risk(JsonNode input) {
        return Risk.SAFE;
    }

    /**
     * Whether "allow for this session" may be offered. False for actions that expose private
     * data (the clipboard), so each read is a fresh decision.
     */
    default boolean sessionApprovable() {
        return true;
    }

    /**
     * Whether the call's arguments leave the machine (a search query, a URL, a message).
     * Once outside content is in the conversation, such calls always need approval, even when
     * they are otherwise SAFE — an injected page could try to smuggle data out through them.
     */
    default boolean sendsDataOut() {
        return false;
    }

    /** One line describing exactly what this call will do, for the approval card and the audit log. */
    String summarize(JsonNode input) throws ToolException;

    ToolOutcome run(JsonNode input) throws ToolException;
}

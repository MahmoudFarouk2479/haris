package dev.haris.llm;

import tools.jackson.databind.JsonNode;

/** One piece of a conversation turn, independent of any provider's wire format. */
public sealed interface Part {

    record Text(String text) implements Part {}

    /** The model asking to run a tool. {@code input} is a MissingNode when the model sent broken JSON. */
    record ToolCall(String id, String name, JsonNode input) implements Part {}

    /** A tool's answer. {@code jpeg} carries a picture (screen or camera) for vision models, or null. */
    record ToolResult(String callId, String content, boolean error, byte[] jpeg) implements Part {

        public ToolResult(String callId, String content, boolean error) {
            this(callId, content, error, null);
        }
    }
}

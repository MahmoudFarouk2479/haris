package dev.haris.llm;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A conversation turn. {@code nativePayload} holds the provider's own representation of an
 * assistant turn when the provider needs it replayed byte-for-byte (Claude's thinking blocks
 * are bound to the exact conversation that produced them).
 */
public record Turn(Role role, List<Part> parts, Object nativePayload) {

    public enum Role { USER, ASSISTANT }

    public static Turn userText(String text) {
        return new Turn(Role.USER, List.of(new Part.Text(text)), null);
    }

    public static Turn toolResults(List<Part.ToolResult> results) {
        return new Turn(Role.USER, List.copyOf(results), null);
    }

    public String text() {
        return parts.stream()
                .filter(p -> p instanceof Part.Text)
                .map(p -> ((Part.Text) p).text())
                .collect(Collectors.joining());
    }

    public List<Part.ToolCall> toolCalls() {
        return parts.stream().filter(p -> p instanceof Part.ToolCall).map(p -> (Part.ToolCall) p).toList();
    }

    public List<Part.ToolResult> toolResults() {
        return parts.stream().filter(p -> p instanceof Part.ToolResult).map(p -> (Part.ToolResult) p).toList();
    }
}

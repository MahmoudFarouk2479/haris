package dev.haris.llm;

import java.util.Map;

/** What the model is told about a tool: name, description and a JSON-schema object. */
public record ToolSpec(String name, String description, Map<String, Object> schema) {}

package dev.haris.tools;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Strict argument readers. The model's arguments are untrusted input: every value is
 * type-checked, length-limited and range-checked before a tool sees it.
 */
public final class Args {

    private Args() {}

    public static void requireObject(JsonNode input) throws ToolException {
        if (input == null || !input.isObject()) {
            throw new ToolException("Invalid arguments (not a JSON object).");
        }
    }

    public static String str(JsonNode input, String field, int maxLength) throws ToolException {
        requireObject(input);
        JsonNode v = input.get(field);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new ToolException("Missing required text argument '" + field + "'.");
        }
        String s = v.asString();
        if (s.length() > maxLength) {
            throw new ToolException("Argument '" + field + "' is too long (max " + maxLength + " characters).");
        }
        if (s.indexOf('\0') >= 0) {
            throw new ToolException("Argument '" + field + "' contains a NUL character.");
        }
        return s;
    }

    public static String optStr(JsonNode input, String field, int maxLength, String fallback) throws ToolException {
        requireObject(input);
        JsonNode v = input.get(field);
        if (v == null || v.isNull() || (v.isString() && v.asString().isBlank())) {
            return fallback;
        }
        return str(input, field, maxLength);
    }

    public static int intIn(JsonNode input, String field, int min, int max, int fallback) throws ToolException {
        requireObject(input);
        JsonNode v = input.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isIntegralNumber()) {
            throw new ToolException("Argument '" + field + "' must be a whole number.");
        }
        long n = v.asLong();
        if (n < min || n > max) {
            throw new ToolException("Argument '" + field + "' must be between " + min + " and " + max + ".");
        }
        return (int) n;
    }

    public static String oneOf(JsonNode input, String field, Set<String> allowed) throws ToolException {
        String s = str(input, field, 40);
        if (!allowed.contains(s)) {
            throw new ToolException("Argument '" + field + "' must be one of " + allowed + ".");
        }
        return s;
    }
}

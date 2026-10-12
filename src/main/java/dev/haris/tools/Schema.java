package dev.haris.tools;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny builder for the JSON schemas tools publish. */
public final class Schema {

    private Schema() {}

    @SafeVarargs
    public static Map<String, Object> object(List<String> required, Map.Entry<String, Map<String, Object>>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> p : props) {
            properties.put(p.getKey(), p.getValue());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> none() {
        return object(List.of());
    }

    public static Map.Entry<String, Map<String, Object>> str(String name, String description) {
        return Map.entry(name, Map.of("type", "string", "description", description));
    }

    public static Map.Entry<String, Map<String, Object>> integer(String name, String description, int min, int max) {
        return Map.entry(name, Map.of("type", "integer", "description", description, "minimum", min, "maximum", max));
    }

    public static Map.Entry<String, Map<String, Object>> oneOf(String name, String description, String... values) {
        return Map.entry(name, Map.of("type", "string", "description", description, "enum", Arrays.asList(values)));
    }
}

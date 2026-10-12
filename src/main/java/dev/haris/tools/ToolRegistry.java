package dev.haris.tools;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.haris.llm.ToolSpec;
import org.springframework.stereotype.Component;

/** Every tool the app ships, discovered as Spring beans. The set is fixed at startup. */
@Component
public class ToolRegistry {

    private final Map<String, Tool> byName;
    private final List<ToolSpec> specs;

    public ToolRegistry(List<Tool> tools) {
        this.byName = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
        // Sorted, so the tool list sent to the model is byte-identical on every request.
        this.specs = tools.stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(t -> new ToolSpec(t.name(), t.description(), t.schema()))
                .toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    public int size() {
        return specs.size();
    }
}

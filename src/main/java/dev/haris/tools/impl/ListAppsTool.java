package dev.haris.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class ListAppsTool implements Tool {

    private final AppCatalog catalog;

    public ListAppsTool(AppCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "list_apps";
    }

    @Override
    public String description() {
        return "List installed apps from the Start menu, optionally filtered by a search term.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of(), Schema.str("query", "Part of the app name, e.g. 'word' or 'spotify'"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String q = Args.optStr(input, "query", 80, "");
        return q.isEmpty() ? "List installed apps" : "Search installed apps for \"" + q + "\"";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String q = Args.optStr(input, "query", 80, "");
        List<AppCatalog.App> apps = q.isEmpty() ? catalog.all() : catalog.match(q);
        if (apps.isEmpty()) {
            return ToolOutcome.ok(q.isEmpty() ? "No apps found." : "No installed app matches \"" + q + "\".");
        }
        String names = apps.stream().limit(150).map(AppCatalog.App::name).collect(Collectors.joining("\n"));
        return ToolOutcome.ok(names + (apps.size() > 150 ? "\n… and " + (apps.size() - 150) + " more" : ""));
    }
}

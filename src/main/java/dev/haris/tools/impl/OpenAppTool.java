package dev.haris.tools.impl;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Opens an app from the Start menu list — by catalogue entry only, never by command line. */
@Component
public class OpenAppTool implements Tool {

    private final AppCatalog catalog;

    public OpenAppTool(AppCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "open_app";
    }

    @Override
    public String description() {
        return "Open an installed app by its Start menu name (use list_apps if unsure of the exact name).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("name"), Schema.str("name", "App name as shown in the Start menu"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Open app: " + resolve(input).name();
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        AppCatalog.App app = resolve(input);
        try {
            // explorer.exe resolves the Start menu entry itself; the AppID is passed as one argument.
            new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + app.appId())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new ToolException("Could not open " + app.name() + ".");
        }
        return ToolOutcome.ok("Opened " + app.name() + ".");
    }

    private AppCatalog.App resolve(JsonNode input) throws ToolException {
        String name = Args.str(input, "name", 120);
        List<AppCatalog.App> matches = catalog.match(name);
        if (matches.isEmpty()) {
            throw new ToolException("No installed app is called \"" + name + "\". Use list_apps to see what is installed.");
        }
        if (matches.size() > 1) {
            throw new ToolException("Several apps match \"" + name + "\": "
                    + matches.stream().map(AppCatalog.App::name).collect(Collectors.joining(", "))
                    + ". Ask the user which one.");
        }
        return matches.getFirst();
    }
}

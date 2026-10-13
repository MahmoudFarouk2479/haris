package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class CreateFolderTool implements Tool {

    private final PathGuard paths;

    public CreateFolderTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "create_folder";
    }

    @Override
    public String description() {
        return "Create a new folder inside a granted folder.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("path"), Schema.str("path", "Absolute path of the new folder"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Create folder " + paths.writableFolder(Args.str(input, "path", 1000));
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        Path dir = paths.writableFolder(Args.str(input, "path", 1000));
        if (Files.exists(dir)) {
            throw new ToolException("Already exists: " + dir);
        }
        try {
            Files.createDirectory(dir);
        } catch (IOException e) {
            throw new ToolException("Could not create " + dir + ".");
        }
        return ToolOutcome.undoable("Created folder " + dir + ".",
                new ToolOutcome.Undo("remove folder " + dir.getFileName() + " (only if still empty)",
                        () -> Files.deleteIfExists(dir)));
    }
}

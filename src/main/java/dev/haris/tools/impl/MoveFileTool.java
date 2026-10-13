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

/** Moves or renames a file or folder within the granted folders. Never overwrites; always undoable. */
@Component
public class MoveFileTool implements Tool {

    private final PathGuard paths;

    public MoveFileTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "move_file";
    }

    @Override
    public String description() {
        return "Move or rename a file or folder. Both locations must be in granted folders; existing files are never overwritten.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("from", "to"),
                Schema.str("from", "Absolute path of the file or folder"),
                Schema.str("to", "Absolute new path, including the name"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        Path from = paths.existing(Args.str(input, "from", 1000));
        return "Move " + from + " → " + paths.moveTarget(from, Args.str(input, "to", 1000));
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        Path from = paths.existing(Args.str(input, "from", 1000));
        Path to = paths.moveTarget(from, Args.str(input, "to", 1000));
        if (paths.roots().contains(from)) {
            throw new ToolException("A granted folder itself cannot be moved.");
        }
        if (Files.exists(to)) {
            throw new ToolException("Something already exists at " + to + ".");
        }
        try {
            Files.move(from, to);
        } catch (IOException e) {
            throw new ToolException("Could not move " + from.getFileName() + ".");
        }
        return ToolOutcome.undoable("Moved to " + to + ".",
                new ToolOutcome.Undo("move " + to.getFileName() + " back", () -> {
                    if (Files.exists(from)) {
                        throw new IOException("the original location is occupied again");
                    }
                    Files.move(to, from);
                }));
    }
}

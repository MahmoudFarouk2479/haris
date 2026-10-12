package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.tools.Args;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Creates, overwrites or appends to a text file in a granted folder. Every change can be undone. */
@Component
public class WriteFileTool implements Tool {

    private static final Set<String> MODES = Set.of("create", "overwrite", "append");
    private static final int MAX_CHARS = 200_000;

    private final PathGuard paths;

    public WriteFileTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write a text file in a granted folder. mode: create (fails if it exists), overwrite, or append.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("path", "content"),
                Schema.str("path", "Absolute file path"),
                Schema.str("content", "Text to write"),
                Schema.oneOf("mode", "Default create", "append", "create", "overwrite"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String mode = input.has("mode") ? Args.oneOf(input, "mode", MODES) : "create";
        Path target = paths.writable(Args.str(input, "path", 1000));
        int chars = Args.str(input, "content", MAX_CHARS).length();
        String verb = switch (mode) {
            case "overwrite" -> "OVERWRITE";
            case "append" -> "Append to";
            default -> "Create";
        };
        return verb + " " + target + " (" + chars + " characters)";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String mode = input.has("mode") ? Args.oneOf(input, "mode", MODES) : "create";
        Path target = paths.writable(Args.str(input, "path", 1000));
        String content = Args.str(input, "content", MAX_CHARS);
        boolean existed = Files.exists(target);
        if (existed && !Files.isRegularFile(target)) {
            throw new ToolException("A folder with that name already exists.");
        }
        try {
            switch (mode) {
                case "create" -> {
                    if (existed) {
                        throw new ToolException("That file already exists. Use mode overwrite or append, or pick another name.");
                    }
                    Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    return ToolOutcome.undoable("Created " + target + ".",
                            new ToolOutcome.Undo("delete " + target.getFileName(), () -> Files.deleteIfExists(target)));
                }
                case "overwrite", "append" -> {
                    byte[] before = existed ? Files.readAllBytes(target) : null;
                    if (mode.equals("append")) {
                        Files.writeString(target, content, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.writeString(target, content, StandardCharsets.UTF_8);
                    }
                    return ToolOutcome.undoable((mode.equals("append") ? "Appended to " : "Wrote ") + target + ".",
                            new ToolOutcome.Undo("restore " + target.getFileName(), () -> restore(target, before)));
                }
                default -> throw new ToolException("Unknown mode.");
            }
        } catch (IOException e) {
            throw new ToolException("Could not write " + target + ".");
        }
    }

    private static void restore(Path target, byte[] before) throws IOException {
        if (before == null) {
            Files.deleteIfExists(target);
        } else {
            Files.write(target, before);
        }
    }
}

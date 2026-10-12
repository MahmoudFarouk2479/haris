package dev.haris.tools.impl;

import java.awt.Desktop;
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

/** Sends a file or folder to the Recycle Bin — never a permanent delete — and asks every time. */
@Component
public class DeleteFileTool implements Tool {

    private final PathGuard paths;

    public DeleteFileTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "delete_file";
    }

    @Override
    public String description() {
        return "Move a file or folder from a granted folder to the Recycle Bin (recoverable from there).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("path"), Schema.str("path", "Absolute path"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CRITICAL;
    }

    @Override
    public boolean sessionApprovable() {
        return false;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Move to Recycle Bin: " + paths.existing(Args.str(input, "path", 1000));
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        Path target = paths.existing(Args.str(input, "path", 1000));
        if (paths.roots().contains(target)) {
            throw new ToolException("A granted folder itself cannot be deleted.");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            throw new ToolException("The Recycle Bin is not available, so nothing was deleted.");
        }
        if (!Desktop.getDesktop().moveToTrash(target.toFile())) {
            throw new ToolException("Windows could not move it to the Recycle Bin.");
        }
        return ToolOutcome.ok("Moved " + target.getFileName() + " to the Recycle Bin. It can be restored from there.");
    }
}

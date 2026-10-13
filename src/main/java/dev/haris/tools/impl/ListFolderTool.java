package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.haris.tools.Args;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class ListFolderTool implements Tool {

    private static final int MAX_ENTRIES = 200;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PathGuard paths;

    public ListFolderTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "list_folder";
    }

    @Override
    public boolean readsOutsideContent() {
        return true;   // file and folder names are chosen by whoever created them (downloads, shares, archives)
    }

    @Override
    public String description() {
        return "List the contents of a granted folder. Without a path, lists the granted folders themselves.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of(), Schema.str("path", "Absolute folder path"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String p = Args.optStr(input, "path", 1000, "");
        return p.isEmpty() ? "List granted folders" : "List folder " + p;
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String raw = Args.optStr(input, "path", 1000, "");
        if (raw.isEmpty()) {
            return ToolOutcome.ok("Granted folders:\n" + paths.roots().stream().map(Path::toString)
                    .collect(Collectors.joining("\n")));
        }
        Path dir = paths.existing(raw);
        if (!Files.isDirectory(dir)) {
            throw new ToolException("Not a folder: " + dir);
        }
        List<String> lines = new ArrayList<>();
        int total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                total++;
                if (lines.size() >= MAX_ENTRIES) {
                    continue;
                }
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (Files.isHidden(p)) {
                    continue;
                }
                lines.add((a.isDirectory() ? "[DIR]  " : "       ") + p.getFileName()
                        + (a.isDirectory() ? "" : "  (" + human(a.size()) + ")")
                        + "  " + DATE.format(a.lastModifiedTime().toInstant()));
            }
        } catch (IOException e) {
            throw new ToolException("Cannot read folder: " + dir);
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        String header = dir + " — " + total + " item(s)" + (total > MAX_ENTRIES ? ", first " + MAX_ENTRIES + " shown" : "");
        return ToolOutcome.ok(header + "\n" + String.join("\n", lines));
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}

package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Finds files by name inside the granted folders. Never follows links out of them. */
@Component
public class SearchFilesTool implements Tool {

    private static final int MAX_RESULTS = 50;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VISITED = 50_000;

    private final PathGuard paths;

    public SearchFilesTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "search_files";
    }

    @Override
    public boolean readsOutsideContent() {
        return true;   // matching paths carry names chosen by whoever created the files
    }

    @Override
    public String description() {
        return "Find files or folders whose name contains the given text, inside the granted folders.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("name"),
                Schema.str("name", "Text the file name contains, e.g. 'invoice' or '.pdf'"),
                Schema.str("folder", "Limit the search to this granted folder (optional)"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Search files named like \"" + Args.str(input, "name", 120) + "\"";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String needle = Args.str(input, "name", 120).toLowerCase(Locale.ROOT);
        String folder = Args.optStr(input, "folder", 1000, "");
        List<Path> starts = folder.isEmpty() ? paths.roots() : List.of(paths.existing(folder));

        List<String> hits = new ArrayList<>();
        int[] visited = {0};
        for (Path start : starts) {
            try {
                Files.walkFileTree(start, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                                if (!dir.equals(start) && (n.startsWith(".") || n.equalsIgnoreCase("node_modules"))) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return check(dir);
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                return check(file);
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }

                            private FileVisitResult check(Path p) {
                                if (++visited[0] > MAX_VISITED || hits.size() >= MAX_RESULTS) {
                                    return FileVisitResult.TERMINATE;
                                }
                                Path name = p.getFileName();
                                if (name != null && name.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                                    hits.add(p.toString());
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                // skip unreadable roots
            }
        }
        if (hits.isEmpty()) {
            return ToolOutcome.ok("No file name contains \"" + needle + "\".");
        }
        return ToolOutcome.ok(hits.size() + " match(es)" + (hits.size() >= MAX_RESULTS ? " (first " + MAX_RESULTS + ")" : "")
                + ":\n" + String.join("\n", hits));
    }
}

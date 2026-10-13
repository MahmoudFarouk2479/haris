package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.PathGuard;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Reads a text file from a granted folder. The content is returned marked as outside data:
 * a document can contain instructions aimed at the assistant, and from this point on every
 * consequential action in the conversation needs explicit approval.
 */
@Component
public class ReadFileTool implements Tool {

    private static final long MAX_BYTES = 256 * 1024;

    private final PathGuard paths;

    public ReadFileTool(PathGuard paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public boolean readsOutsideContent() {
        return true;   // a file's text can be written by anyone
    }

    @Override
    public String description() {
        return "Read a text file (txt, md, csv, json, code…) from a granted folder, up to 256 KB.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("path"), Schema.str("path", "Absolute file path"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Read file " + Args.str(input, "path", 1000);
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        Path file = paths.existing(Args.str(input, "path", 1000));
        if (!Files.isRegularFile(file)) {
            throw new ToolException("Not a file: " + file);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new ToolException("The file is " + ListFolderTool.human(size) + "; the limit is 256 KB.");
            }
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
                if (bytes[i] == 0) {
                    throw new ToolException("This is a binary file, not text.");
                }
            }
            return ToolOutcome.untrusted("File: " + file + "\n\n" + decode(bytes));
        } catch (IOException e) {
            throw new ToolException("Cannot read " + file + ".");
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1256"));   // common legacy Arabic encoding
        }
    }
}

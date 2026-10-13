package dev.haris.tools.impl;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class ClipboardWriteTool implements Tool {

    @Override
    public String name() {
        return "clipboard_write";
    }

    @Override
    public String description() {
        return "Put text on the clipboard so the user can paste it (e.g. a translation or a fixed version).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("text"), Schema.str("text", "Text to copy"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String text = Args.str(input, "text", 50_000);
        return "Copy to clipboard: \"" + (text.length() > 100 ? text.substring(0, 100) + "…" : text) + "\"";
    }

    @Override
    public String auditSummary(JsonNode input) throws ToolException {
        return "Copy " + Args.str(input, "text", 50_000).length() + " characters to clipboard";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String text = Args.str(input, "text", 50_000);
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String previous = clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
                    ? (String) clipboard.getData(DataFlavor.stringFlavor) : null;
            clipboard.setContents(new StringSelection(text), null);
            if (previous == null) {
                return ToolOutcome.ok("Copied.");
            }
            return ToolOutcome.undoable("Copied.", new ToolOutcome.Undo("restore the previous clipboard text",
                    () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(previous), null)));
        } catch (Exception e) {
            throw new ToolException("The clipboard is busy or unavailable.");
        }
    }
}

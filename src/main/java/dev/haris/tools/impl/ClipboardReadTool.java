package dev.haris.tools.impl;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.Map;

import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Reads the clipboard. It often holds passwords and one-time codes, so the user approves
 * every single read, and the text is treated as outside content.
 */
@Component
public class ClipboardReadTool implements Tool {

    @Override
    public String name() {
        return "clipboard_read";
    }

    @Override
    public String description() {
        return "Read the text currently on the clipboard (to translate, summarise, explain or fix it).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.none();
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public boolean sessionApprovable() {
        return false;
    }

    @Override
    public String summarize(JsonNode input) {
        return "Read your clipboard (may contain private text)";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return ToolOutcome.ok("The clipboard does not contain text.");
            }
            String text = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (text.length() > 20_000) {
                text = text.substring(0, 20_000) + "\n… (truncated)";
            }
            return ToolOutcome.untrusted(text);
        } catch (Exception e) {
            throw new ToolException("The clipboard is busy or unavailable.");
        }
    }
}

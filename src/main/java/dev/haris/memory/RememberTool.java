package dev.haris.memory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Adds one fact to long-term memory. Memory outlives the conversation, so a planted "fact"
 * would be a lasting prompt injection: every write is shown to the user for approval (and,
 * once outside content is in the conversation, approved one by one).
 */
@Component
public class RememberTool implements Tool {

    private static final Set<String> CATEGORIES = Set.of("preference", "person", "project", "routine", "fact");

    /** Things that must never be stored, whatever the model or a web page says. */
    private static final Pattern SECRET = Pattern.compile(
            "(?iu)(password|passcode|passphrase|pin code|\\bpin\\b|otp|one[- ]time code|api[ _-]?key|secret key|token|"
                    + "كلمة (ال)?سر|باسورد|الرقم السري|sk-[a-z0-9]|\\b(?:\\d[ -]?){13,19}\\b|\\biban\\b)");

    private final MemoryService memory;

    public RememberTool(MemoryService memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String description() {
        return "Save one lasting fact about the user (a preference, a person, a project, a routine) to encrypted memory. "
                + "One short sentence. Never passwords, codes, card or account numbers.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("fact", "category"),
                Schema.str("fact", "One short sentence, e.g. 'Prefers replies in Egyptian Arabic.'"),
                Schema.oneOf("category", "Kind of fact", "fact", "person", "preference", "project", "routine"));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Remember: \"" + fact(input) + "\"";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String text = fact(input);
        String category = Args.oneOf(input, "category", CATEGORIES);
        try {
            MemoryStore.Fact f = memory.store().add(text, category);
            return ToolOutcome.undoable("Remembered (id " + f.id() + ").",
                    new ToolOutcome.Undo("forget \"" + text + "\"", () -> memory.store().remove(f.id())));
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static String fact(JsonNode input) throws ToolException {
        String text = Args.str(input, "fact", MemoryStore.MAX_FACT_CHARS).strip();
        if (SECRET.matcher(text).find()) {
            throw new ToolException("That looks like a secret (password, code, key or account number); it will not be stored.");
        }
        return text;
    }
}

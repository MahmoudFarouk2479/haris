package dev.haris.tools.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class RemindersTool implements Tool {

    private static final Set<String> ACTIONS = Set.of("add", "list", "cancel");
    private static final DateTimeFormatter SHOW =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final ReminderService reminders;

    public RemindersTool(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Override
    public String name() {
        return "reminders";
    }

    @Override
    public String description() {
        return "Add, list or cancel reminders (they pop up as notifications while Haris is running). "
                + "For add, give either in_minutes or at (local time, yyyy-MM-ddTHH:mm).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("action"),
                Schema.oneOf("action", "What to do", "add", "cancel", "list"),
                Schema.str("text", "Reminder text (for add)"),
                Schema.integer("in_minutes", "Minutes from now (for add)", 1, 60 * 24 * 60),
                Schema.str("at", "Local date-time yyyy-MM-ddTHH:mm (for add)"),
                Schema.str("id", "Reminder id (for cancel)"));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return switch (Args.oneOf(input, "action", ACTIONS)) {
            case "add" -> "Set reminder \"" + Args.str(input, "text", 300) + "\" for " + SHOW.format(due(input));
            case "cancel" -> "Cancel reminder " + Args.str(input, "id", 20);
            default -> "List reminders";
        };
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        switch (Args.oneOf(input, "action", ACTIONS)) {
            case "add" -> {
                String text = Args.str(input, "text", 300);
                Instant due = due(input);
                try {
                    ReminderService.Reminder r = reminders.add(text, due);
                    return ToolOutcome.undoable("Reminder " + r.id() + " set for " + SHOW.format(due) + ".",
                            new ToolOutcome.Undo("cancel reminder \"" + text + "\"", () -> reminders.cancel(r.id())));
                } catch (IllegalStateException e) {
                    throw new ToolException(e.getMessage());
                }
            }
            case "cancel" -> {
                String id = Args.str(input, "id", 20);
                return ToolOutcome.ok(reminders.cancel(id) ? "Cancelled." : "No reminder with id " + id + ".");
            }
            default -> {
                List<ReminderService.Reminder> list = reminders.pending();
                if (list.isEmpty()) {
                    return ToolOutcome.ok("No pending reminders.");
                }
                return ToolOutcome.ok(list.stream()
                        .map(r -> r.id() + "  " + SHOW.format(Instant.ofEpochMilli(r.dueEpochMs())) + "  " + r.text())
                        .collect(Collectors.joining("\n")));
            }
        }
    }

    private static Instant due(JsonNode input) throws ToolException {
        if (input.has("in_minutes")) {
            return Instant.now().plusSeconds(60L * Args.intIn(input, "in_minutes", 1, 60 * 24 * 60, 0));
        }
        String at = Args.optStr(input, "at", 30, "");
        if (at.isEmpty()) {
            throw new ToolException("Give either in_minutes or at.");
        }
        try {
            Instant due = LocalDateTime.parse(at.length() == 16 ? at : at.substring(0, Math.min(at.length(), 19)))
                    .atZone(ZoneId.systemDefault()).toInstant();
            if (due.isBefore(Instant.now())) {
                throw new ToolException("That time is in the past.");
            }
            return due;
        } catch (DateTimeParseException e) {
            throw new ToolException("Use the format yyyy-MM-ddTHH:mm for 'at'.");
        }
    }
}

package dev.haris.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Volume and media keys, sent as virtual key presses — exactly what the keyboard's media keys do. */
@Component
public class MediaControlTool implements Tool {

    private static final Set<String> ACTIONS =
            Set.of("volume_up", "volume_down", "mute_toggle", "play_pause", "next_track", "previous_track");

    @Override
    public String name() {
        return "media_control";
    }

    @Override
    public String description() {
        return "Change the system volume or control media playback. Each volume step is about 2%.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("action"),
                Schema.oneOf("action", "What to do", ACTIONS.stream().sorted().toArray(String[]::new)),
                Schema.integer("steps", "Volume steps for volume_up/volume_down (1 step ≈ 2%)", 1, 50));
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        String action = Args.oneOf(input, "action", ACTIONS);
        int steps = Args.intIn(input, "steps", 1, 50, 5);
        return action.startsWith("volume_") ? action.replace('_', ' ') + " by ~" + (steps * 2) + "%" : action.replace('_', ' ');
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        if (!Win32.isWindows()) {
            throw new ToolException("Media control is only implemented for Windows.");
        }
        String action = Args.oneOf(input, "action", ACTIONS);
        int steps = Args.intIn(input, "steps", 1, 50, 5);
        try {
            switch (action) {
                case "volume_up" -> Win32.press(Win32.VK_VOLUME_UP, steps);
                case "volume_down" -> Win32.press(Win32.VK_VOLUME_DOWN, steps);
                case "mute_toggle" -> Win32.press(Win32.VK_VOLUME_MUTE, 1);
                case "play_pause" -> Win32.press(Win32.VK_MEDIA_PLAY_PAUSE, 1);
                case "next_track" -> Win32.press(Win32.VK_MEDIA_NEXT_TRACK, 1);
                case "previous_track" -> Win32.press(Win32.VK_MEDIA_PREV_TRACK, 1);
                default -> throw new ToolException("Unknown action");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted.");
        }
        if (action.equals("volume_up") || action.equals("volume_down")) {
            byte reverse = action.equals("volume_up") ? Win32.VK_VOLUME_DOWN : Win32.VK_VOLUME_UP;
            return ToolOutcome.undoable("Done.", new ToolOutcome.Undo(
                    "volume back " + (action.equals("volume_up") ? "down" : "up") + " ~" + (steps * 2) + "%",
                    () -> Win32.press(reverse, steps)));
        }
        return ToolOutcome.ok("Done.");
    }
}

package dev.haris.tools.impl;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Lock, sleep, restart or shut down. Restart and shutdown are scheduled 60 seconds out, so
 * there is always a window to cancel (cancel_shutdown, or Undo in the HUD).
 */
@Component
public class PowerTool implements Tool {

    private static final Set<String> ACTIONS = Set.of("lock", "sleep", "restart", "shutdown", "cancel_shutdown");

    @Override
    public String name() {
        return "power";
    }

    @Override
    public String description() {
        return "Lock the screen, sleep, restart or shut down the computer (restart/shutdown wait 60 s and can be cancelled).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("action"),
                Schema.oneOf("action", "Power action", ACTIONS.stream().sorted().toArray(String[]::new)));
    }

    @Override
    public Risk risk(JsonNode input) {
        String action = input.path("action").asString();
        return switch (action) {
            case "cancel_shutdown" -> Risk.SAFE;
            case "lock" -> Risk.CONFIRM;
            default -> Risk.CRITICAL;
        };
    }

    @Override
    public boolean sessionApprovable() {
        return false;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return switch (Args.oneOf(input, "action", ACTIONS)) {
            case "lock" -> "Lock the screen";
            case "sleep" -> "Put the computer to sleep";
            case "restart" -> "RESTART the computer in 60 seconds";
            case "shutdown" -> "SHUT DOWN the computer in 60 seconds";
            default -> "Cancel a pending restart/shutdown";
        };
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        if (!Win32.isWindows()) {
            throw new ToolException("Power control is only implemented for Windows.");
        }
        String action = Args.oneOf(input, "action", ACTIONS);
        switch (action) {
            case "lock" -> {
                if (!Win32.User32.INSTANCE.LockWorkStation()) {
                    throw new ToolException("Windows refused to lock.");
                }
                return ToolOutcome.ok("Screen locked.");
            }
            case "sleep" -> {
                Win32.PowrProf.INSTANCE.SetSuspendState(false, false, false);
                return ToolOutcome.ok("Going to sleep.");
            }
            case "restart", "shutdown" -> {
                String flag = action.equals("restart") ? "/r" : "/s";
                exec("shutdown.exe", flag, "/t", "60", "/c", "Haris: " + action + " requested. Run 'shutdown /a' to cancel.");
                return ToolOutcome.undoable("Scheduled " + action + " in 60 seconds.",
                        new ToolOutcome.Undo("cancel the " + action, () -> exec("shutdown.exe", "/a")));
            }
            default -> {
                exec("shutdown.exe", "/a");
                return ToolOutcome.ok("Any pending restart or shutdown was cancelled.");
            }
        }
    }

    private static void exec(String... command) throws ToolException {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor();
        } catch (IOException e) {
            throw new ToolException("Could not run the power command.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted.");
        }
    }
}

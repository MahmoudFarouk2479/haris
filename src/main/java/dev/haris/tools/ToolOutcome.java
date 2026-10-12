package dev.haris.tools;

/**
 * What a tool hands back to the model.
 *
 * @param text      the result, in plain text
 * @param untrusted the content came from outside the user's control (a file, the clipboard, a web
 *                  page, the screen) and may contain instructions planted by someone else
 * @param undo      how to reverse the action, or null when it cannot be reversed
 * @param jpeg      a picture for the model to look at, or null
 */
public record ToolOutcome(String text, boolean untrusted, Undo undo, byte[] jpeg) {

    public static ToolOutcome ok(String text) {
        return new ToolOutcome(text, false, null, null);
    }

    public static ToolOutcome untrusted(String text) {
        return new ToolOutcome(text, true, null, null);
    }

    public static ToolOutcome undoable(String text, Undo undo) {
        return new ToolOutcome(text, false, undo, null);
    }

    /** Anything seen on a screen or through a camera is outside content (it can show planted text). */
    public static ToolOutcome picture(String text, byte[] jpeg) {
        return new ToolOutcome(text, true, null, jpeg);
    }

    /** A reversal the user can trigger from the HUD. */
    public record Undo(String description, Action action) {}

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}

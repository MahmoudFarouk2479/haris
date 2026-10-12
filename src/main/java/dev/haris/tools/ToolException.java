package dev.haris.tools;

/** A tool failure whose message is safe to show the user and the model. */
public class ToolException extends Exception {

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}

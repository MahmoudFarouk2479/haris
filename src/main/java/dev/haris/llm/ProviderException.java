package dev.haris.llm;

/** A failure whose message is safe to show the user (no keys, no stack traces). */
public class ProviderException extends RuntimeException {

    public ProviderException(String userMessage) {
        super(userMessage);
    }

    public ProviderException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}

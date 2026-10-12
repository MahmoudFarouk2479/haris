package dev.haris.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * One language-model backend. A provider only produces text and tool *requests*;
 * it never executes anything itself — that is the agent's job, behind the permission gate.
 */
public interface ChatProvider {

    /** Short label for the HUD, e.g. "Claude · claude-opus-5-5". */
    String label();

    /**
     * Runs one model call over {@code history}, streaming visible text to {@code onDelta}.
     * Throws {@link ProviderException} with a user-safe message.
     */
    ProviderReply complete(String systemPrompt, List<Turn> history, List<ToolSpec> tools, Consumer<String> onDelta);

    /** Model ids this backend offers, for the settings panel. Empty when unknown. */
    List<String> listModels();
}

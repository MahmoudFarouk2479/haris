package dev.haris.settings;

public enum ProviderKind {
    /** Claude, through the official Anthropic SDK. */
    ANTHROPIC,
    /** Ollama running on this machine. */
    OLLAMA,
    /** LM Studio's local server on this machine. */
    LM_STUDIO,
    /** Any other OpenAI-compatible endpoint (OpenAI, Gemini, a LAN box) — HTTPS only. */
    OPENAI_COMPATIBLE,
    /**
     * Ollama running in a Google Colab notebook, reached through the notebook's HTTPS tunnel and
     * its token-checking proxy (colab/haris_colab.ipynb). Conversations leave the machine.
     */
    COLAB
}

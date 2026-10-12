package dev.haris.llm;

/**
 * One model response.
 *
 * @param turn             the assistant turn to append to the history, verbatim
 * @param untrustedContent the response carried outside content (e.g. web-search results)
 * @param pauseTurn        a server-side tool paused mid-turn; send the history back to continue
 * @param notice           something the user should know (e.g. tools were unavailable)
 */
public record ProviderReply(Turn turn, boolean untrustedContent, boolean pauseTurn, String notice) {}

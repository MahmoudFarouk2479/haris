package dev.haris.agent;

/** Everything the agent reports to the HUD while it works. */
public interface AgentListener {

    void onMeta(String providerLabel);

    void onDelta(String text);

    /** status: running | done | denied | error */
    void onTool(String callId, String tool, String summary, String status, String detail);

    void onConfirm(ConfirmationBroker.Request request);

    void onNotice(String message);

    void onSecurity(boolean externalContent);

    /** A picture was shared with the model; shown in the transcript so the user sees exactly what went. */
    void onImage(String callId, String dataUrl);
}

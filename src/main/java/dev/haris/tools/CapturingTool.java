package dev.haris.tools;

import tools.jackson.databind.JsonNode;

/**
 * A tool whose output is a picture taken on this machine (the screen). The agent captures
 * first — into memory only — so the approval card can show the user the exact image, and the
 * picture is handed to the model only after the user approves that image.
 */
public interface CapturingTool extends Tool {

    /** A local capture. Nothing leaves the machine at this point. */
    record Capture(byte[] jpeg, String description) {}

    Capture capture(JsonNode input) throws ToolException;

    /** Called only after approval, with the very capture the user saw. */
    default ToolOutcome deliver(Capture capture) {
        return ToolOutcome.picture(capture.description(), capture.jpeg());
    }

    @Override
    default ToolOutcome run(JsonNode input) throws ToolException {
        return deliver(capture(input));
    }
}

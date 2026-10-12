package dev.haris.vision;

import java.io.IOException;
import java.util.Map;

import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * One webcam photo. Unlike the screen, the camera is not touched before approval: turning it on
 * is itself the sensitive act, so the user approves first and the camera light means "approved".
 */
@Component
public class LookThroughCameraTool implements Tool {

    private final CameraBridge camera;

    public LookThroughCameraTool(CameraBridge camera) {
        this.camera = camera;
    }

    @Override
    public String name() {
        return "look_through_camera";
    }

    @Override
    public String description() {
        return "Take one photo with the webcam to see the user or something they hold up. The user approves each photo.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.none();
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public boolean sessionApprovable() {
        return false;
    }

    @Override
    public String summarize(JsonNode input) {
        return "Turn on the camera for ONE photo";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        try {
            byte[] jpeg = camera.takePhoto();
            return ToolOutcome.picture("Webcam photo of the user's surroundings, shown to you as an image.", jpeg);
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
    }
}

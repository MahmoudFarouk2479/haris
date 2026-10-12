package dev.haris.vision;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vision")
public class VisionController {

    public static final int MAX_PHOTO_BYTES = 4 * 1024 * 1024;

    private final CameraBridge camera;

    public VisionController(CameraBridge camera) {
        this.camera = camera;
    }

    /** The HUD's photo for camera request {id}. Unknown or expired ids are refused. */
    @PostMapping(value = "/camera/{id}", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Map<String, Boolean>> photo(@PathVariable String id, @RequestBody byte[] jpeg) {
        if (jpeg.length < 3 || jpeg.length > MAX_PHOTO_BYTES
                || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8 || (jpeg[2] & 0xFF) != 0xFF) {
            return ResponseEntity.badRequest().body(Map.of("ok", false));
        }
        boolean ok = camera.deliver(id, jpeg, null);
        return ok ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(410).body(Map.of("ok", false));
    }

    /** The HUD could not take the photo (permission refused, no camera). */
    @PostMapping("/camera/{id}/error")
    public Map<String, Boolean> photoFailed(@PathVariable String id, @RequestParam(defaultValue = "unknown") String reason) {
        String message = switch (reason) {
            case "denied" -> "Camera permission was refused in the HUD.";
            case "missing" -> "No camera was found.";
            default -> "The camera could not be used.";
        };
        return Map.of("ok", camera.deliver(id, null, message));
    }
}

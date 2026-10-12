package dev.haris.vision;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.haris.agent.EventHub;
import org.springframework.stereotype.Component;

/**
 * Asks the HUD for one camera photo. The webcam belongs to the HUD window (the browser asks the
 * user for camera permission and shows its own indicator); the core only ever receives a single
 * still, for a request it created itself, identified by a random id.
 */
@Component
public class CameraBridge {

    private static final long TIMEOUT_SECONDS = 25;

    private final EventHub events;
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    public CameraBridge(EventHub events) {
        this.events = events;
    }

    public byte[] takePhoto() throws IOException {
        String id = UUID.randomUUID().toString();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            events.publish("camera", Map.of("id", id));
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("The camera did not respond (is the HUD open and the camera allowed?).");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        } finally {
            pending.remove(id);
        }
    }

    /** The HUD's answer: a JPEG, or an error message when the camera could not be used. */
    public boolean deliver(String id, byte[] jpeg, String error) {
        CompletableFuture<byte[]> f = pending.get(id);
        if (f == null) {
            return false;
        }
        if (error != null) {
            return f.completeExceptionally(new IOException(error));
        }
        try {
            return f.complete(Images.sanitize(jpeg));
        } catch (IOException e) {
            return f.completeExceptionally(new IOException("The camera sent an unreadable image."));
        }
    }
}

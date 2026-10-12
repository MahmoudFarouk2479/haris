package dev.haris.agent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes events that are not part of a chat reply (reminders, camera requests) to every open
 * HUD window, and knows whether any HUD window is still open: each one keeps this stream
 * connected, and a heartbeat every 10 s detects windows that went away.
 */
@Component
public class EventHub {

    /** Published when the last HUD window has been gone for {@link #GRACE_SECONDS}. */
    public record HudClosed() {}

    static final long GRACE_SECONDS = 20;

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher events;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hud-presence");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingClose;

    public EventHub(ApplicationEventPublisher events) {
        this.events = events;
        timer.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        clients.add(emitter);
        cancelClose();
        emitter.onCompletion(() -> drop(emitter));
        emitter.onTimeout(() -> drop(emitter));
        emitter.onError(e -> drop(emitter));
        return emitter;
    }

    public void publish(String event, Object data) {
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        }
    }

    public boolean hudConnected() {
        return !clients.isEmpty();
    }

    private void heartbeat() {
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                drop(emitter);
            }
        }
    }

    private synchronized void drop(SseEmitter emitter) {
        if (clients.remove(emitter) && clients.isEmpty() && pendingClose == null) {
            // A reload reconnects within seconds; only a window that stays gone counts as closed.
            pendingClose = timer.schedule(() -> {
                synchronized (this) {
                    pendingClose = null;
                }
                if (clients.isEmpty()) {
                    events.publishEvent(new HudClosed());
                }
            }, GRACE_SECONDS, TimeUnit.SECONDS);
        }
    }

    private synchronized void cancelClose() {
        if (pendingClose != null) {
            pendingClose.cancel(false);
            pendingClose = null;
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}

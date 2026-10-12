package dev.haris.chat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.haris.agent.AgentListener;
import dev.haris.agent.AgentService;
import dev.haris.agent.ConfirmationBroker;
import dev.haris.agent.ConversationService;
import dev.haris.llm.ChatProvider;
import dev.haris.llm.ProviderException;
import dev.haris.llm.ProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_MESSAGE_CHARS = 8000;

    private final AgentService agent;
    private final ProviderFactory providers;
    private final ConversationService conversations;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public ChatController(AgentService agent, ProviderFactory providers, ConversationService conversations) {
        this.agent = agent;
        this.providers = providers;
        this.conversations = conversations;
    }

    public record ChatRequest(String message) {}

    @PostMapping(value = "/chat", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> chat(@RequestBody ChatRequest request) {
        String text = request.message() == null ? "" : request.message().strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_CHARS) {
            return ResponseEntity.badRequest().build();
        }
        if (!busy.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                agent.run(text, new SseListener(emitter));
                send(emitter, "done", Map.of());
            } catch (ProviderException e) {
                log.warn("Provider error: {}", e.getMessage());
                send(emitter, "error", Map.of("message", e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Chat failed", e);
                send(emitter, "error", Map.of("message", "Something went wrong in the core. See the console log."));
            } finally {
                busy.set(false);
                emitter.complete();
            }
        });
        return ResponseEntity.ok(emitter);
    }

    @PostMapping("/chat/reset")
    public Map<String, Boolean> reset() {
        conversations.reset();
        return Map.of("ok", true);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        try {
            ChatProvider p = providers.current();
            return Map.of("provider", p.label(), "models", p.listModels());
        } catch (ProviderException e) {
            return Map.of("provider", "", "models", List.of(), "error", e.getMessage());
        }
    }

    /** Streams the agent's progress to the HUD as server-sent events. */
    private record SseListener(SseEmitter emitter) implements AgentListener {

        @Override
        public void onMeta(String providerLabel) {
            send(emitter, "meta", Map.of("provider", providerLabel));
        }

        @Override
        public void onDelta(String text) {
            send(emitter, "delta", Map.of("text", text));
        }

        @Override
        public void onTool(String callId, String tool, String summary, String status, String detail) {
            Map<String, Object> data = new HashMap<>();
            data.put("id", callId);
            data.put("tool", tool);
            data.put("summary", summary);
            data.put("status", status);
            if (detail != null) {
                data.put("detail", detail);
            }
            send(emitter, "tool", data);
        }

        @Override
        public void onConfirm(ConfirmationBroker.Request request) {
            send(emitter, "confirm", request);
        }

        @Override
        public void onNotice(String message) {
            send(emitter, "notice", Map.of("message", message));
        }

        @Override
        public void onSecurity(boolean externalContent) {
            send(emitter, "security", Map.of("externalContent", externalContent));
        }

        @Override
        public void onImage(String callId, String dataUrl) {
            send(emitter, "image", Map.of("id", callId, "src", dataUrl));
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // The window was closed mid-reply; the agent finishes unseen (pending approvals time out as denied).
        }
    }
}

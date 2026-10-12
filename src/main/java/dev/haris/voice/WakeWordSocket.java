package dev.haris.voice;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.haris.security.SessionToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * Receives microphone audio from the HUD while it waits for "Hey Haris" and reports detections
 * back. Nothing is buffered or recorded here: each 80 ms frame is checked in memory and dropped.
 *
 * <p>Browsers cannot put headers on a WebSocket, so the session token travels as the
 * requested sub-protocol; it is checked by {@code LocalAccessFilter} before the upgrade and
 * echoed back here, as the protocol requires.
 */
@Component
public class WakeWordSocket extends BinaryWebSocketHandler implements SubProtocolCapable {

    public static final String WAKE_WORD = "hey_haris";
    private static final int MAX_FRAME_BYTES = 32_000;   // 1 s of 16 kHz 16-bit audio

    private final WakeWordDetector detector;
    private final SessionToken token;
    private final JsonMapper json;
    private final Map<String, WakeWordDetector.Stream> streams = new ConcurrentHashMap<>();

    public WakeWordSocket(WakeWordDetector detector, SessionToken token, JsonMapper json) {
        this.detector = detector;
        this.token = token;
        this.json = json;
    }

    @Override
    public List<String> getSubProtocols() {
        // Spring matches requested sub-protocols in lower case. The exact, case-sensitive token
        // has already been verified by LocalAccessFilter; this only lets the handshake echo it.
        return List.of(token.value().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!streams.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("one microphone stream at a time"));
            return;
        }
        var stream = detector.open();
        if (stream.isEmpty()) {
            session.close(CloseStatus.SERVICE_RESTARTED.withReason("wake-word model not installed"));
            return;
        }
        streams.put(session.getId(), stream.get());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        WakeWordDetector.Stream stream = streams.get(session.getId());
        ByteBuffer buf = message.getPayload();
        if (stream == null || buf.remaining() == 0 || buf.remaining() > MAX_FRAME_BYTES || buf.remaining() % 2 != 0) {
            return;
        }
        byte[] pcm = new byte[buf.remaining()];
        buf.get(pcm);
        boolean heard;
        synchronized (stream) {
            heard = stream.accept(pcm);
        }
        if (heard && session.isOpen()) {
            session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type", "wake", "name", WAKE_WORD))));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WakeWordDetector.Stream stream = streams.remove(session.getId());
        if (stream != null) {
            synchronized (stream) {
                stream.close();
            }
        }
    }
}

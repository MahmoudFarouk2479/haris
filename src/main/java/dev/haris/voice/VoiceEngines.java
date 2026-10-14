package dev.haris.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Speech-to-text and text-to-speech through the local Wyoming containers. Audio is held in
 * memory for the length of one request and never written anywhere.
 */
@Component
public class VoiceEngines {

    public static final int RATE = 16_000;
    private static final int CHUNK = 8192;

    private final JsonMapper json;
    private final int sttPort;
    private final int ttsPort;

    public VoiceEngines(JsonMapper json,
                        @Value("${haris.voice.stt-port:10300}") int sttPort,
                        @Value("${haris.voice.tts-port:10200}") int ttsPort) {
        this.json = json;
        this.sttPort = sttPort;
        this.ttsPort = ttsPort;
    }

    /**
     * Transcribes 16 kHz, 16-bit, mono little-endian PCM. {@code language} may be null for auto-detect.
     * Retries twice: right after Haris starts the containers, Whisper accepts the connection while it is
     * still loading its model and then drops it.
     */
    public String transcribe(byte[] pcm, String language) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return transcribeOnce(pcm, language);
            } catch (IOException e) {
                if (attempt == 3) {
                    throw e;
                }
                pause(3000);
            }
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
    }

    private String transcribeOnce(byte[] pcm, String language) throws IOException {
        try (WyomingClient c = new WyomingClient(sttPort, 120_000, json)) {
            Map<String, Object> request = new LinkedHashMap<>();
            if (language != null) {
                request.put("language", language);
            }
            c.send("transcribe", request);
            Map<String, Object> format = Map.of("rate", RATE, "width", 2, "channels", 1);
            c.send("audio-start", format);
            for (int i = 0; i < pcm.length; i += CHUNK) {
                byte[] chunk = java.util.Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + CHUNK));
                c.send("audio-chunk", format, chunk);
            }
            c.send("audio-stop", Map.of());
            for (;;) {
                WyomingClient.Event e = c.read();
                if (e.type().equals("transcript")) {
                    return e.data().path("text").asString().strip();
                }
                if (e.type().equals("error")) {
                    throw new IOException("speech-to-text error: " + e.data().path("text").asString());
                }
            }
        }
    }

    /** Synthesises speech and returns a complete WAV file (retried, as for transcription). */
    public byte[] synthesize(String text, String voice) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return synthesizeOnce(text, voice);
            } catch (IOException e) {
                if (attempt == 3) {
                    throw e;
                }
                pause(2000);
            }
        }
    }

    private byte[] synthesizeOnce(String text, String voice) throws IOException {
        try (WyomingClient c = new WyomingClient(ttsPort, 120_000, json)) {
            c.send("synthesize", Map.of("text", text, "voice", Map.of("name", voice)));
            int rate = 22_050;
            int width = 2;
            int channels = 1;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            for (;;) {
                WyomingClient.Event e = c.read();
                switch (e.type()) {
                    case "audio-start" -> {
                        rate = e.data().path("rate").asInt(rate);
                        width = e.data().path("width").asInt(width);
                        channels = e.data().path("channels").asInt(channels);
                    }
                    case "audio-chunk" -> pcm.write(e.payload());
                    case "audio-stop" -> {
                        return wav(pcm.toByteArray(), rate, width, channels);
                    }
                    case "error" -> throw new IOException("text-to-speech error: " + e.data().path("text").asString());
                    default -> { /* ignore */ }
                }
            }
        }
    }

    /** Which engines answer. Short timeouts: this feeds the HUD status panel. */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("stt", describe(sttPort) != null);
        s.put("tts", describe(ttsPort) != null);
        return s;
    }

    /** True when the speech-to-text engine answers a Wyoming "describe" (its model is loaded). */
    public boolean sttReady() {
        return describe(sttPort) != null;
    }

    private JsonNode describe(int port) {
        try (WyomingClient c = new WyomingClient(port, 1500, json)) {
            c.send("describe", Map.of());
            WyomingClient.Event e = c.read();
            return e.type().equals("info") ? e.data() : null;
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] wav(byte[] pcm, int rate, int width, int channels) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * width * channels).putShort((short) (width * channels))
                .putShort((short) (width * 8));
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}

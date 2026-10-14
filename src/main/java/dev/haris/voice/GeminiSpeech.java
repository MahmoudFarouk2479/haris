package dev.haris.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dev.haris.security.SecretStore;
import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Natural spoken replies from Google's Gemini text-to-speech model, which speaks Egyptian Arabic.
 * Opt-in: only the reply text is sent (never the user's voice), with the user's own API key from
 * the OS vault. After a failure Gemini is skipped for a minute and the local Piper engine speaks.
 */
@Component
public class GeminiSpeech {

    static final URI ENDPOINT = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions");
    public static final String MODEL = "gemini-3.8-flash-tts";
    /** Gemini's prebuilt voices. */
    public static final Set<String> VOICES = Set.of(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
            "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat");

    private static final int RATE = 24_000;
    private static final long COOL_DOWN_MS = 60_000;
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final SettingsService settings;
    private final SecretStore secrets;
    private final JsonMapper json;
    private volatile long failedAt;

    public GeminiSpeech(SettingsService settings, SecretStore secrets, JsonMapper json) {
        this.settings = settings;
        this.secrets = secrets;
        this.json = json;
    }

    /** True when the user chose Gemini for spoken replies and saved a key (the route the HUD shows). */
    public boolean selected() {
        return "gemini".equals(settings.get().ttsEngine()) && secrets.has(SecretStore.GEMINI);
    }

    /** The key to use now, or empty when Piper should speak (not chosen, no key, or cooling down). */
    public Optional<String> available() {
        if (!"gemini".equals(settings.get().ttsEngine()) || System.currentTimeMillis() - failedAt < COOL_DOWN_MS) {
            return Optional.empty();
        }
        return secrets.get(SecretStore.GEMINI);
    }

    /** A complete WAV file for {@code text} in the voice chosen in Settings. */
    public byte[] speak(String key, String text) throws IOException {
        Settings s = settings.get();
        String voice = VOICES.contains(s.geminiVoice()) ? s.geminiVoice() : "Charon";
        try {
            byte[] wav = synthesize(ENDPOINT, key, MODEL, text, voice, json);
            failedAt = 0;
            return wav;
        } catch (IOException e) {
            failedAt = System.currentTimeMillis();
            throw e;
        }
    }

    static byte[] synthesize(URI endpoint, String key, String model, String text, String voice, JsonMapper json)
            throws IOException {
        // Delivery instructions go in speech_metadata; the text itself is read verbatim.
        String style = ARABIC.matcher(text).find()
                ? "Natural Egyptian Arabic (Masri) accent; calm, warm and conversational."
                : "Calm, clear and warm; conversational.";
        Map<String, Object> body = Map.of(
                "model", model,
                "input", List.of(Map.of("type", "user_input", "content", List.of(Map.of(
                        "type", "text", "text", text,
                        "annotations", List.of(Map.of("type", "speech_metadata", "style", style)))))),
                "response_format", Map.of("type", "audio"),
                "generation_config", Map.of("speech_config", List.of(Map.of("voice", voice))));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        if (response.statusCode() != 200) {
            // The status only: an error body could echo the key or the text back into the log.
            throw new IOException("Gemini speech answered HTTP " + response.statusCode());
        }
        byte[] audio;
        try {
            String data = null;
            for (JsonNode step : json.readTree(response.body()).path("steps")) {
                if (step.path("type").asString().equals("model_output")) {
                    for (JsonNode part : step.path("content")) {
                        if (part.path("type").asString().equals("audio")) {
                            data = part.path("data").asString();
                        }
                    }
                }
            }
            if (data == null || data.isEmpty()) {
                throw new IOException("Gemini speech sent no audio.");
            }
            audio = Base64.getDecoder().decode(data);
        } catch (RuntimeException e) {
            throw new IOException("Gemini speech sent an unreadable reply.");
        }
        boolean riff = audio.length >= 4 && new String(audio, 0, 4, StandardCharsets.US_ASCII).equals("RIFF");
        return riff ? audio : wav(audio, RATE);
    }

    /** Wraps 16-bit mono little-endian PCM in a WAV header. */
    static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }
}

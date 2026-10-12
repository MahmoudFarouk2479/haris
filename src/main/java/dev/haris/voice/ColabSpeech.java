package dev.haris.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import dev.haris.security.SecretStore;
import dev.haris.settings.ProviderKind;
import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Speech-to-text on the Colab notebook's GPU, through the same token-checking proxy as the model.
 * Used only when the user already chose the Colab engine (so the conversation goes there anyway),
 * the setting is on and an access token is saved. After a failure Colab is skipped for a minute,
 * so the local engine answers without waiting on a dead tunnel.
 */
@Component
public class ColabSpeech {

    static final String PATH = "/haris/v1/transcribe";
    private static final long COOL_DOWN_MS = 60_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public record Target(URI endpoint, String token) {}

    private final SettingsService settings;
    private final SecretStore secrets;
    private final JsonMapper json;
    private volatile long failedAt;

    public ColabSpeech(SettingsService settings, SecretStore secrets, JsonMapper json) {
        this.settings = settings;
        this.secrets = secrets;
        this.json = json;
    }

    /** Where to send speech, or empty when it stays on this PC. */
    public Optional<Target> target() {
        Settings s = settings.get();
        if (s.provider() != ProviderKind.COLAB || !Boolean.TRUE.equals(s.colabStt())
                || s.colabBaseUrl() == null || s.colabBaseUrl().isBlank()) {
            return Optional.empty();
        }
        return secrets.get(SecretStore.COLAB).map(token -> new Target(URI.create(s.colabBaseUrl() + PATH), token));
    }

    /** Like {@link #target()}, but empty while cooling down after a failure. */
    public Optional<Target> available() {
        return System.currentTimeMillis() - failedAt < COOL_DOWN_MS ? Optional.empty() : target();
    }

    public String transcribe(Target target, byte[] pcm, String language) throws IOException {
        try {
            String text = transcribe(target, pcm, language, json);
            failedAt = 0;
            return text;
        } catch (IOException e) {
            failedAt = System.currentTimeMillis();
            throw e;
        }
    }

    /** Raw 16 kHz 16-bit mono PCM in, transcript out. {@code language} may be null for auto-detect. */
    static String transcribe(Target target, byte[] pcm, String language, JsonMapper json) throws IOException {
        URI uri = language == null ? target.endpoint() : URI.create(target.endpoint() + "?language=" + language);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + target.token())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Colab speech-to-text answered HTTP " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("text").asString().strip();
        } catch (RuntimeException e) {
            throw new IOException("Colab speech-to-text sent an unreadable reply.");
        }
    }
}

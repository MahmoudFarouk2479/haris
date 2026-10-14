package dev.haris.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiSpeechTest {

    private static final String KEY = "test-gemini-key";
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private HttpServer server;
    private volatile byte[] audio = GeminiSpeech.wav(new byte[4800], 24_000);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/interactions", exchange -> {
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            request.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            boolean ok = KEY.equals(keyHeader.get());
            String body = ok
                    ? "{\"steps\":[{\"type\":\"user_input\"},{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\""
                            + Base64.getEncoder().encodeToString(audio) + "\"}]}]}"
                    : "{\"error\":{\"message\":\"API key not valid\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/moved/v1beta/interactions", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/interactions");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI endpoint(String prefix) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + prefix + "/v1beta/interactions");
    }

    @Test
    void sendsTheTextVoiceAndKeyAndReturnsTheWav() throws IOException {
        byte[] wav = GeminiSpeech.synthesize(endpoint(""), KEY, "gemini-3.8-flash-tts", "Hello there.", "Charon", json);

        assertThat(wav).isEqualTo(audio);
        assertThat(keyHeader.get()).isEqualTo(KEY);
        JsonNode body = request.get();
        assertThat(body.path("model").asString()).isEqualTo("gemini-3.8-flash-tts");
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("audio");
        assertThat(body.path("generation_config").path("speech_config").path(0).path("voice").asString()).isEqualTo("Charon");
        JsonNode text = body.path("input").path(0).path("content").path(0);
        assertThat(text.path("text").asString()).isEqualTo("Hello there.");
        assertThat(text.path("annotations").path(0).path("type").asString()).isEqualTo("speech_metadata");
    }

    @Test
    void arabicTextAsksForAnEgyptianDelivery() throws IOException {
        GeminiSpeech.synthesize(endpoint(""), KEY, "gemini-3.8-flash-tts", "أهلاً يا محمود", "Charon", json);
        String style = request.get().path("input").path(0).path("content").path(0)
                .path("annotations").path(0).path("style").asString();
        assertThat(style).containsIgnoringCase("Egyptian");
    }

    @Test
    void rawPcmIsWrappedInAWavHeader() throws IOException {
        audio = new byte[4800];   // headerless L16, as the streaming format would send
        byte[] wav = GeminiSpeech.synthesize(endpoint(""), KEY, "gemini-3.8-flash-tts", "Hi.", "Kore", json);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(wav).hasSize(44 + 4800);
    }

    @Test
    void aRejectedKeyIsAnErrorWithoutTheServersWords() {
        assertThatThrownBy(() -> GeminiSpeech.synthesize(endpoint(""), "wrong", "gemini-3.8-flash-tts", "Hi.", "Kore", json))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("400")
                .hasMessageNotContaining("API key not valid");
    }

    @Test
    void redirectsAreNotFollowed() {
        assertThatThrownBy(() -> GeminiSpeech.synthesize(endpoint("/moved"), KEY, "gemini-3.8-flash-tts", "Hi.", "Kore", json))
                .isInstanceOf(IOException.class).hasMessageContaining("302");
    }

    @Test
    void onlyKnownVoicesAreAccepted() {
        assertThat(GeminiSpeech.VOICES).contains("Charon", "Kore", "Puck").hasSize(30);
    }
}

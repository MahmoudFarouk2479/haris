package dev.haris.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ColabSpeechTest {

    private static final String TOKEN = "test-token-0123456789abcdefghijkl";
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<Integer> bytes = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ColabSpeech.PATH, exchange -> {
            boolean ok = ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            bytes.set(exchange.getRequestBody().readAllBytes().length);
            byte[] body = (ok ? "{\"text\": \" افتح المفكرة \"}" : "unauthorized").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port() + ColabSpeech.PATH);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private ColabSpeech.Target target(String path, String token) {
        return new ColabSpeech.Target(URI.create("http://127.0.0.1:" + port() + path), token);
    }

    @Test
    void sendsTheAudioWithTheTokenAndLanguage() throws IOException {
        String text = ColabSpeech.transcribe(target(ColabSpeech.PATH, TOKEN), new byte[6400], "ar", json);
        assertThat(text).isEqualTo("افتح المفكرة");
        assertThat(query.get()).isEqualTo("language=ar");
        assertThat(bytes.get()).isEqualTo(6400);
    }

    @Test
    void autoDetectSendsNoLanguage() throws IOException {
        ColabSpeech.transcribe(target(ColabSpeech.PATH, TOKEN), new byte[6400], null, json);
        assertThat(query.get()).isNull();
    }

    @Test
    void aRejectedTokenIsAnError() {
        assertThatThrownBy(() -> ColabSpeech.transcribe(target(ColabSpeech.PATH, "wrong"), new byte[6400], null, json))
                .isInstanceOf(IOException.class).hasMessageContaining("401");
    }

    @Test
    void redirectsAreNotFollowed() {
        assertThatThrownBy(() -> ColabSpeech.transcribe(target("/moved", TOKEN), new byte[6400], null, json))
                .isInstanceOf(IOException.class).hasMessageContaining("302");
    }
}

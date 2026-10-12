package dev.haris.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** A tunnel that stops mid-reply (a Colab session ending) must not hang the conversation. */
class StalledStreamTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000);   // then silence
            } catch (InterruptedException ignored) {
                // test over
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void givesUpWhenTheServerGoesSilent() {
        var provider = new OpenAiCompatibleProvider("Colab", "http://127.0.0.1:" + server.getAddress().getPort(),
                "qwen2.5:7b", "token", JsonMapper.builder().build(), Duration.ofSeconds(1));
        StringBuilder streamed = new StringBuilder();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> provider.complete("system", List.of(), List.of(), streamed::append))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("stopped answering")
                .hasMessageContaining("Colab session may have ended");
        assertThat(streamed.toString()).isEqualTo("Hello");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }
}

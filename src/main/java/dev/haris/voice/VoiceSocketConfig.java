package dev.haris.voice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class VoiceSocketConfig implements WebSocketConfigurer {

    private final WakeWordSocket wakeWord;

    public VoiceSocketConfig(WakeWordSocket wakeWord) {
        this.wakeWord = wakeWord;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // LocalAccessFilter already pins Origin and Host to this server; this is a second fence.
        registry.addHandler(wakeWord, "/ws/wake")
                .setAllowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxTextMessageBufferSize(4 * 1024);
        container.setMaxSessionIdleTimeout(10 * 60 * 1000L);
        return container;
    }
}

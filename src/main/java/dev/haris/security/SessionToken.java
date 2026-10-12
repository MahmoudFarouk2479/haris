package dev.haris.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A fresh secret for every launch. The UI receives it once, in the URL fragment of the
 * window the core opens itself (fragments are never sent to a server or written to logs),
 * and must present it on every API call. Another local program or a web page in the
 * user's normal browser cannot guess it, so it cannot drive the assistant.
 */
@Component
public class SessionToken {

    private final String value;
    private volatile int port = -1;

    public SessionToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @EventListener
    public void onServerReady(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    public String value() {
        return value;
    }

    public int port() {
        return port;
    }

    /** Constant-time comparison, so response timing reveals nothing about the token. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }
}

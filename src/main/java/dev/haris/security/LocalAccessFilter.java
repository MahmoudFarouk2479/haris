package dev.haris.security;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request passes four gates before it reaches a controller:
 * <ol>
 *   <li>it comes from the loopback interface;</li>
 *   <li>its Host header names this server (defeats DNS-rebinding from a web page);</li>
 *   <li>for state-changing calls, its Origin is this server (defeats cross-site requests);</li>
 *   <li>for /api, it carries this launch's session token.</li>
 * </ol>
 * It also sets a strict Content-Security-Policy, so even a rendering bug cannot load
 * or run anything that did not ship with the app.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalAccessFilter.class);

    static final String TOKEN_HEADER = "X-Haris-Token";
    private static final long MAX_API_BODY_BYTES = 256 * 1024;
    private static final long MAX_AUDIO_BODY_BYTES = 30L * 16_000 * 2 + 1024;   // 30 s of 16 kHz speech
    private static final long MAX_PHOTO_BODY_BYTES = 4L * 1024 * 1024;          // one webcam still
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "media-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'");

    private final SessionToken token;

    public LocalAccessFilter(SessionToken token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isLoopback(request.getRemoteAddr())) {
            deny(response, request, "non-loopback client");
            return;
        }
        if (!isOwnHost(request.getHeader("Host"))) {
            deny(response, request, "unexpected Host header");
            return;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/ws/")) {
            // WebSocket upgrade: browsers cannot add headers, so the token arrives as the
            // requested sub-protocol. Origin is always sent on a WebSocket handshake.
            if (!isOwnOrigin(request.getHeader("Origin"))) {
                deny(response, request, "cross-origin WebSocket");
                return;
            }
            String protocols = request.getHeader("Sec-WebSocket-Protocol");
            boolean ok = protocols != null && java.util.Arrays.stream(protocols.split(","))
                    .map(String::strip).anyMatch(token::matches);
            if (!ok) {
                deny(response, request, "WebSocket without session token");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        boolean api = uri.startsWith("/api/");
        if (api) {
            String origin = request.getHeader("Origin");
            boolean safe = SAFE_METHODS.contains(request.getMethod());
            if ((origin != null || !safe) && !isOwnOrigin(origin)) {
                deny(response, request, "cross-origin request");
                return;
            }
            if (!token.matches(request.getHeader(TOKEN_HEADER))) {
                deny(response, request, "missing or wrong session token");
                return;
            }
            long limit = uri.equals("/api/voice/transcribe") ? MAX_AUDIO_BODY_BYTES
                    : uri.startsWith("/api/vision/camera/") ? MAX_PHOTO_BODY_BYTES
                    : MAX_API_BODY_BYTES;
            if (!safe && request.getContentLengthLong() < 0 && request.getHeader("Transfer-Encoding") != null) {
                // Chunked bodies would slip past the size check; the HUD never sends them.
                response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED);
                return;
            }
            if (request.getContentLengthLong() > limit) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                return;
            }
        }

        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        // Microphone and camera only for this page itself (voice, one-shot photos); the rest never.
        response.setHeader("Permissions-Policy",
                "camera=(self), microphone=(self), geolocation=(), payment=(), usb=(), display-capture=()");
        if (api) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    private boolean isLoopback(String remoteAddr) {
        try {
            return remoteAddr != null && InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isOwnHost(String host) {
        int port = token.port();
        return host != null && port > 0
                && (host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port));
    }

    private boolean isOwnOrigin(String origin) {
        int port = token.port();
        return origin != null && port > 0
                && (origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port));
    }

    private void deny(HttpServletResponse response, HttpServletRequest request, String reason) throws IOException {
        log.warn("Blocked {} {} from {}: {}", request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr(), reason);
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}

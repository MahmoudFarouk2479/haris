package dev.haris.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The filter is the only thing between other local programs and the assistant's controls. */
class LocalAccessFilterTest {

    private static final int PORT = 8080;
    private static final String TOKEN = "good-token";

    private LocalAccessFilter filter;

    @BeforeEach
    void setUp() {
        SessionToken token = mock(SessionToken.class);
        when(token.port()).thenReturn(PORT);
        when(token.matches(TOKEN)).thenReturn(true);
        filter = new LocalAccessFilter(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;x/memory", "/%61pi/memory", "/ws;x/wake", "/api/../x", "//api/memory",
            "/api/memory/.", "/assets/..", "/api\\memory"})
    void nonCanonicalPathsAreRefusedEvenWithTheToken(String uri) throws Exception {
        Result r = send(withToken(request("GET", uri)));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void apiNeedsTheToken() throws Exception {
        assertThat(send(request("GET", "/api/memory")).status()).isEqualTo(403);
        assertThat(send(withToken(request("GET", "/api/memory"))).passed()).isTrue();
    }

    @Test
    void hudStaticFilesNeedNoToken() throws Exception {
        for (String uri : new String[] {"/", "/index.html", "/mic-worklet.js", "/assets/index-Ci8fgFwu.js"}) {
            Result r = send(request("GET", uri));
            assertThat(r.passed()).as(uri).isTrue();
            assertThat(r.response().getHeader("Content-Security-Policy")).as(uri).contains("script-src 'self'");
        }
        assertThat(send(request("HEAD", "/")).passed()).isTrue();
    }

    @Test
    void thePageIsRevalidatedOnEveryLaunchSoAnUpgradeNeverShowsTheOldHud() throws Exception {
        for (String uri : new String[] {"/", "/index.html", "/mic-worklet.js"}) {
            assertThat(send(request("GET", uri)).response().getHeader("Cache-Control")).as(uri).isEqualTo("no-cache");
        }
        // Hashed bundles change name with every build, so they can be kept for good.
        assertThat(send(request("GET", "/assets/index-Ci8fgFwu.js")).response().getHeader("Cache-Control"))
                .isEqualTo("max-age=31536000, immutable");
    }

    @Test
    void staticFilesWithAPercentInTheQueryStringStillLoad() throws Exception {
        MockHttpServletRequest r = request("GET", "/");
        r.setQueryString("x=%41");
        assertThat(send(r).passed()).isTrue();
    }

    @Test
    void onlyFlatAssetNamesAreStatic() throws Exception {
        assertThat(send(request("GET", "/assets/sub/x.js")).status()).isEqualTo(403);
    }

    @Test
    void writesToStaticPathsNeedTheToken() throws Exception {
        assertThat(send(request("POST", "/")).status()).isEqualTo(403);
    }

    @Test
    void unknownPathsNeedTheToken() throws Exception {
        assertThat(send(request("GET", "/actuator")).status()).isEqualTo(403);
        assertThat(send(withToken(request("GET", "/actuator"))).passed()).isTrue();
    }

    @Test
    void aForeignOriginIsRefusedEvenWithTheToken() throws Exception {
        MockHttpServletRequest r = withToken(request("POST", "/api/undo"));
        r.addHeader("Origin", "http://evil.example");
        assertThat(send(r).status()).isEqualTo(403);
    }

    @Test
    void protectedResponsesAreNotCached() throws Exception {
        Result r = send(withToken(request("GET", "/api/memory")));
        assertThat(r.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void webSocketNeedsItsOwnOriginAndTheTokenAsSubProtocol() throws Exception {
        MockHttpServletRequest ok = request("GET", "/ws/wake");
        ok.addHeader("Origin", "http://127.0.0.1:" + PORT);
        ok.addHeader("Sec-WebSocket-Protocol", TOKEN);
        assertThat(send(ok).passed()).isTrue();

        MockHttpServletRequest noToken = request("GET", "/ws/wake");
        noToken.addHeader("Origin", "http://127.0.0.1:" + PORT);
        assertThat(send(noToken).status()).isEqualTo(403);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private record Result(int status, boolean passed, MockHttpServletResponse response) {}

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.addHeader("Host", "127.0.0.1:" + PORT);
        return r;
    }

    private static MockHttpServletRequest withToken(MockHttpServletRequest r) {
        r.addHeader(LocalAccessFilter.TOKEN_HEADER, TOKEN);
        return r;
    }

    private Result send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response.getStatus(), chain.getRequest() != null, response);
    }
}

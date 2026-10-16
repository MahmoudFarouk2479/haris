package dev.haris.settings;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.haris.llm.ProviderFactory;
import dev.haris.security.LocalAccessFilter;
import dev.haris.security.SecretStore;
import dev.haris.security.SessionToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Settings window's "Remove" for a stored key: DELETE /api/secrets/{name}, behind the same gate
 * as every other change (loopback, own Host, own Origin, session token), for the user's own keys only.
 */
class SecretRemovalTest {

    private static final int PORT = 8080;
    private static final String TOKEN = "good-token";
    private static final String OWN_ORIGIN = "http://127.0.0.1:" + PORT;

    private SecretStore secrets;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        secrets = mock(SecretStore.class);
        when(secrets.has(anyString())).thenReturn(true);
        SessionToken token = mock(SessionToken.class);
        when(token.port()).thenReturn(PORT);
        when(token.matches(TOKEN)).thenReturn(true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(settings, secrets, mock(ProviderFactory.class)))
                .addFilters(new LocalAccessFilter(token))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {SecretStore.ANTHROPIC, SecretStore.OPENAI_COMPATIBLE, SecretStore.COLAB, SecretStore.GEMINI})
    void eachUserKeyCanBeRemovedAndTheAnswerOnlySaysWhetherKeysAreStored(String name) throws Exception {
        when(secrets.has(name)).thenReturn(false);

        mvc.perform(fromHud(delete("/api/secrets/" + name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secrets['" + name + "']").value(false))
                .andExpect(content().string(not(containsString("value"))));
        verify(secrets).delete(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {SecretStore.MEMORY_KEY, SecretStore.SEARXNG_SECRET, "nope"})
    void internalSecretsAndUnknownNamesAreRefused(String name) throws Exception {
        mvc.perform(fromHud(delete("/api/secrets/" + name)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown key: " + name));
        verify(secrets, never()).delete(anyString());
    }

    @Test
    void aForeignOriginOrNoOriginIsRefusedEvenWithTheToken() throws Exception {
        mvc.perform(delete("/api/secrets/anthropic").header("Host", "127.0.0.1:" + PORT)
                        .header("X-Haris-Token", TOKEN).header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/secrets/anthropic").header("Host", "127.0.0.1:" + PORT)
                        .header("X-Haris-Token", TOKEN))
                .andExpect(status().isForbidden());
        verify(secrets, never()).delete(anyString());
    }

    @Test
    void theSessionTokenIsRequired() throws Exception {
        mvc.perform(delete("/api/secrets/anthropic").header("Host", "127.0.0.1:" + PORT).header("Origin", OWN_ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/secrets/anthropic").header("Host", "127.0.0.1:" + PORT).header("Origin", OWN_ORIGIN)
                        .header("X-Haris-Token", "wrong"))
                .andExpect(status().isForbidden());
        verify(secrets, never()).delete(anyString());
    }

    @Test
    void aForeignHostIsRefused() throws Exception {
        mvc.perform(delete("/api/secrets/anthropic").header("Host", "evil.example:" + PORT)
                        .header("Origin", OWN_ORIGIN).header("X-Haris-Token", TOKEN))
                .andExpect(status().isForbidden());
        verify(secrets, never()).delete(anyString());
    }

    private static MockHttpServletRequestBuilder fromHud(MockHttpServletRequestBuilder request) {
        return request.header("Host", "127.0.0.1:" + PORT).header("Origin", OWN_ORIGIN).header("X-Haris-Token", TOKEN);
    }
}

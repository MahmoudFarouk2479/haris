package dev.haris.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.haris.llm.ProviderFactory;
import dev.haris.security.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

/** A setting the API refuses comes back as a 400 the HUD can show, and is never saved. */
class SettingsControllerTest {

    @Test
    void anUnknownInterfaceLanguageIsRefusedWithAMessage() throws Exception {
        SettingsService settings = mock(SettingsService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new SettingsController(settings, mock(SecretStore.class), mock(ProviderFactory.class))).build();
        String body = JsonMapper.builder().build().writeValueAsString(Settings.defaults())
                .replace("\"uiLanguage\":\"en\"", "\"uiLanguage\":\"fr\"");

        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for uiLanguage."));
        verify(settings, never()).update(any());
    }
}

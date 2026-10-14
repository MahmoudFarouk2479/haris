package dev.haris.system;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

/** GET /api/weather/now: the home city's weather for the status greeting, or 204 when there is none. */
class WeatherControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc(String homeCity, WeatherService.Fetcher fetcher) {
        SettingsService settings = mock(SettingsService.class);
        Settings d = Settings.defaults();
        String body = json.writeValueAsString(d).replace("\"homeCity\":\"\"", "\"homeCity\":\"" + homeCity + "\"");
        when(settings.get()).thenReturn(json.readValue(body, Settings.class));
        return MockMvcBuilders.standaloneSetup(new WeatherController(settings, new WeatherService(json, fetcher))).build();
    }

    @Test
    void noHomeCityMeansNoContentAndNoRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        mvc("", (url, timeout) -> {
            calls.incrementAndGet();
            return new WeatherService.Response(200, "{}");
        }).perform(get("/api/weather/now")).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(calls.get()).isZero();
    }

    @Test
    void aFailedLookupMeansNoContent() throws Exception {
        mvc("Cairo", (url, timeout) -> new WeatherService.Response(503, ""))
                .perform(get("/api/weather/now")).andExpect(status().isNoContent());
    }

    @Test
    void theHomeCityWeatherAsJson() throws Exception {
        mvc("Cairo", (url, timeout) -> new WeatherService.Response(200,
                url.contains("geocoding") ? WeatherServiceTest.GEOCODE : WeatherServiceTest.FORECAST_NOW))
                .perform(get("/api/weather/now").param("lang", "ar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Cairo"))
                .andExpect(jsonPath("$.country").value("Egypt"))
                .andExpect(jsonPath("$.tempC").value(24))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.highC").value(29))
                .andExpect(jsonPath("$.lowC").value(18));
    }
}

package dev.haris.tools.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import dev.haris.system.WeatherService;
import dev.haris.tools.ToolException;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The weather tool's answer is unchanged now that the Open-Meteo lookup is shared. */
class WeatherToolTest {

    private static final String GEOCODE = """
            {"results":[{"name":"Cairo","country":"Egypt","latitude":30.06,"longitude":31.25}]}""";
    private static final String FORECAST = """
            {"current":{"temperature_2m":23.6,"apparent_temperature":22.9,"relative_humidity_2m":40,
                        "weather_code":2,"wind_speed_10m":11.5},
             "daily":{"time":["2026-10-14","2026-10-15"],"weather_code":[0,61],
                      "temperature_2m_max":[29.4,27.0],"temperature_2m_min":[18.2,17.5],
                      "precipitation_probability_max":[0,60]}}""";

    private final JsonMapper json = JsonMapper.builder().build();

    private WeatherTool tool(WeatherService.Fetcher fetcher) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        return new WeatherTool(settings, new WeatherService(json, fetcher));
    }

    @Test
    void theAnswerKeepsItsFormat() throws Exception {
        WeatherTool tool = tool((url, timeout) -> new WeatherService.Response(200,
                url.contains("geocoding") ? GEOCODE : FORECAST));
        String out = tool.run(json.createObjectNode().put("city", "Cairo")).text();
        assertThat(out).isEqualTo("""
                Cairo, Egypt
                Now: partly cloudy, 23.6°C (feels 22.9°C), humidity 40%, wind 11.5 km/h
                2026-10-14: clear sky, 18.2–29.4°C, rain chance 0%
                2026-10-15: rain, 17.5–27.0°C, rain chance 60%
                """);
    }

    @Test
    void errorsKeepTheirWording() {
        assertThatThrownBy(() -> tool((url, timeout) -> new WeatherService.Response(200, "{}"))
                .run(json.createObjectNode().put("city", "Atlantis")))
                .isInstanceOf(ToolException.class).hasMessage("I could not find a place called Atlantis.");
        assertThatThrownBy(() -> tool((url, timeout) -> new WeatherService.Response(502, ""))
                .run(json.createObjectNode().put("city", "Cairo")))
                .hasMessage("The weather service returned HTTP 502.");
        assertThatThrownBy(() -> tool((url, timeout) -> {
            throw new java.io.IOException("refused");
        }).run(json.createObjectNode().put("city", "Cairo")))
                .hasMessage("The weather service is unreachable.");
    }
}

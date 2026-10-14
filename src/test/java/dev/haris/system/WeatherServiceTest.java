package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** The status greeting's weather: one place lookup and one forecast, never the network in tests. */
class WeatherServiceTest {

    static final String GEOCODE = """
            {"results":[{"name":"Cairo","country":"Egypt","latitude":30.06,"longitude":31.25}]}""";
    static final String FORECAST_NOW = """
            {"current":{"temperature_2m":23.6,"weather_code":0},
             "daily":{"time":["2026-10-14"],"temperature_2m_max":[29.4],"temperature_2m_min":[18.2]}}""";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void mapsACannedOpenMeteoAnswerToNow() {
        List<String> urls = new ArrayList<>();
        WeatherService weather = new WeatherService(json, (url, timeout) -> {
            urls.add(url);
            return new WeatherService.Response(200, url.contains("geocoding") ? GEOCODE : FORECAST_NOW);
        });

        Optional<WeatherService.Now> now = weather.now("Cairo", "en");

        assertThat(now).contains(new WeatherService.Now("Cairo", "Egypt", 24, 0, 29, 18));
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).contains("name=Cairo").contains("language=en");
        assertThat(urls.get(1)).contains("latitude=30.06").contains("longitude=31.25").contains("weather_code");
    }

    @Test
    void asksForArabicPlaceNamesInTheArabicInterface() {
        List<String> urls = new ArrayList<>();
        WeatherService weather = new WeatherService(json, (url, timeout) -> {
            urls.add(url);
            return new WeatherService.Response(200, url.contains("geocoding") ? GEOCODE : FORECAST_NOW);
        });
        weather.now("Cairo", "ar");
        assertThat(urls.get(0)).contains("language=ar");
        weather.now("Cairo", "fr");
        assertThat(urls.get(2)).contains("language=en");
    }

    @Test
    void anUnknownPlaceAnErrorOrAnUnreachableServiceGiveNothing() {
        assertThat(new WeatherService(json, (url, timeout) -> new WeatherService.Response(200, "{}"))
                .now("Atlantis", "en")).isEmpty();
        assertThat(new WeatherService(json, (url, timeout) -> new WeatherService.Response(500, ""))
                .now("Cairo", "en")).isEmpty();
        assertThat(new WeatherService(json, (url, timeout) -> {
            throw new java.net.http.HttpTimeoutException("slow");
        }).now("Cairo", "en")).isEmpty();
        assertThat(new WeatherService(json, (url, timeout) -> new WeatherService.Response(200, GEOCODE))
                .now(" ", "en")).isEmpty();
    }
}

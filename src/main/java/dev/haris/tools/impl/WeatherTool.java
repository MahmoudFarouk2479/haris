package dev.haris.tools.impl;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.haris.settings.SettingsService;
import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Weather from Open-Meteo: free, no account, no API key, no tracking. Only the city name
 * leaves the machine.
 */
@Component
public class WeatherTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final JsonMapper json;
    private final SettingsService settings;

    public WeatherTool(JsonMapper json, SettingsService settings) {
        this.json = json;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "Current weather and a 3-day forecast for a city (defaults to the user's home city).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of(), Schema.str("city", "City name, e.g. Cairo"));
    }

    @Override
    public boolean sendsDataOut() {
        return true;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Get the weather for " + city(input);
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String city = city(input);
        JsonNode place = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                + URLEncoder.encode(city, StandardCharsets.UTF_8)).path("results").path(0);
        if (place.isMissingNode()) {
            throw new ToolException("I could not find a place called " + city + ".");
        }
        double lat = place.path("latitude").asDouble();
        double lon = place.path("longitude").asDouble();
        JsonNode w = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=3");

        JsonNode c = w.path("current");
        StringBuilder out = new StringBuilder();
        out.append(place.path("name").asString()).append(", ").append(place.path("country").asString()).append('\n')
                .append("Now: ").append(describe(c.path("weather_code").asInt()))
                .append(", ").append(c.path("temperature_2m").asDouble()).append("°C (feels ")
                .append(c.path("apparent_temperature").asDouble()).append("°C), humidity ")
                .append(c.path("relative_humidity_2m").asInt()).append("%, wind ")
                .append(c.path("wind_speed_10m").asDouble()).append(" km/h\n");
        JsonNode d = w.path("daily");
        for (int i = 0; i < d.path("time").size(); i++) {
            out.append(d.path("time").path(i).asString()).append(": ")
                    .append(describe(d.path("weather_code").path(i).asInt())).append(", ")
                    .append(d.path("temperature_2m_min").path(i).asDouble()).append("–")
                    .append(d.path("temperature_2m_max").path(i).asDouble()).append("°C, rain chance ")
                    .append(d.path("precipitation_probability_max").path(i).asInt()).append("%\n");
        }
        return ToolOutcome.ok(out.toString());
    }

    private String city(JsonNode input) throws ToolException {
        String fallback = settings.get().homeCity();
        String city = Args.optStr(input, "city", 80, fallback == null ? "" : fallback);
        if (city.isBlank()) {
            throw new ToolException("Which city? (You can also set a home city in Settings.)");
        }
        return city;
    }

    private JsonNode get(String url) throws ToolException {
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new ToolException("The weather service returned HTTP " + res.statusCode() + ".");
            }
            return json.readTree(res.body());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("The weather service is unreachable.");
        }
    }

    private static String describe(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1, 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 66, 67, 80, 81, 82 -> "rain";
            case 71, 73, 75, 77, 85, 86 -> "snow";
            case 95, 96, 99 -> "thunderstorm";
            default -> "code " + code;
        };
    }
}

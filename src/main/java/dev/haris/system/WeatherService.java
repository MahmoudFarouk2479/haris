package dev.haris.system;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Open-Meteo: free, no account, no API key, no tracking. Only the city name leaves the machine.
 * Shared by the weather tool and the first-launch status greeting.
 */
@Component
public class WeatherService {

    /** One GET. */
    @FunctionalInterface
    public interface Fetcher {
        Response get(String url, Duration timeout) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    /** A place Open-Meteo knows. */
    public record Place(String name, String country, double latitude, double longitude) {}

    /** The status greeting's weather, in whole degrees. */
    public record Now(String city, String country, long tempC, int code, long highC, long lowC) {}

    /** A failure whose message is fixed wording (never text from the remote service). */
    public static final class WeatherException extends Exception {
        public WeatherException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration QUICK = Duration.ofMillis(3500);   // a cold DNS + TLS start can take ~3 s

    private final JsonMapper json;
    private final Fetcher fetcher;
    private final Map<String, Place> places = new ConcurrentHashMap<>();

    @Autowired
    public WeatherService(JsonMapper json) {
        this(json, WeatherService::httpGet);
    }

    public WeatherService(JsonMapper json, Fetcher fetcher) {
        this.json = json;
        this.fetcher = fetcher;
    }

    /** The best match for a city name, with its name in the given language (en or ar). */
    public Optional<Place> find(String city, String language, Duration timeout) throws WeatherException {
        JsonNode place = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=" + language
                + "&format=json&name=" + URLEncoder.encode(city, StandardCharsets.UTF_8), timeout)
                .path("results").path(0);
        if (place.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(new Place(place.path("name").asString(), place.path("country").asString(),
                place.path("latitude").asDouble(), place.path("longitude").asDouble()));
    }

    /** A forecast for a place; {@code query} is the Open-Meteo parameter list (current=…&daily=…). */
    public JsonNode forecast(Place place, String query, Duration timeout) throws WeatherException {
        return get("https://api.open-meteo.com/v1/forecast?latitude=" + place.latitude()
                + "&longitude=" + place.longitude() + "&" + query, timeout);
    }

    /** Current temperature and condition plus today's range, or nothing if it cannot be had quickly. */
    public Optional<Now> now(String city, String language) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        String lang = "ar".equals(language) ? "ar" : "en";
        try {
            String key = lang + ":" + city.strip().toLowerCase();
            Place place = places.get(key);
            if (place == null) {
                Optional<Place> found = find(city.strip(), lang, QUICK);
                if (found.isEmpty()) {
                    return Optional.empty();
                }
                place = found.get();
                places.put(key, place);
            }
            JsonNode w = forecast(place, "current=temperature_2m,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", QUICK);
            JsonNode c = w.path("current");
            JsonNode d = w.path("daily");
            if (!c.path("temperature_2m").isNumber() || !d.path("temperature_2m_max").path(0).isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new Now(place.name(), place.country(),
                    Math.round(c.path("temperature_2m").asDouble()), c.path("weather_code").asInt(-1),
                    Math.round(d.path("temperature_2m_max").path(0).asDouble()),
                    Math.round(d.path("temperature_2m_min").path(0).asDouble())));
        } catch (WeatherException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private JsonNode get(String url, Duration timeout) throws WeatherException {
        Response res;
        try {
            res = fetcher.get(url, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("The weather service is unreachable.");
        } catch (Exception e) {
            throw new WeatherException("The weather service is unreachable.");
        }
        if (res.status() != 200) {
            throw new WeatherException("The weather service returned HTTP " + res.status() + ".");
        }
        try {
            return json.readTree(res.body());
        } catch (RuntimeException e) {
            throw new WeatherException("The weather service is unreachable.");
        }
    }

    /**
     * The request timeout covers only the wait for the headers, so the whole exchange (body included)
     * is bounded here too: a server that stalls mid-body cannot hold the caller's thread.
     */
    private static Response httpGet(String url, Duration timeout) throws IOException, InterruptedException {
        java.util.concurrent.CompletableFuture<HttpResponse<String>> call = HTTP.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> res = call.get(timeout.toMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return new Response(res.statusCode(), res.body());
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            call.cancel(true);
            throw new IOException("weather request failed", e);
        }
    }
}

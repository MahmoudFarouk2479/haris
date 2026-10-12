package dev.haris.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Queries the local SearXNG container (127.0.0.1 only) and returns de-duplicated results. */
@Component
public class SearchClient {

    public record Result(String title, String url, String snippet, String engine) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final JsonMapper json;
    private final int port;

    public SearchClient(JsonMapper json, @Value("${haris.search.port:10500}") int port) {
        this.json = json;
        this.port = port;
    }

    public List<Result> search(String query, String language, int count) throws IOException {
        String url = "http://127.0.0.1:" + port + "/search?format=json&safesearch=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + (language == null ? "" : "&language=" + language);
        HttpResponse<String> res;
        try {
            res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("The local search engine is not running. Start it with: docker compose -f search/compose.yaml up -d");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        if (res.statusCode() != 200) {
            throw new IOException("The local search engine answered HTTP " + res.statusCode() + ".");
        }
        List<Result> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode r : json.readTree(res.body()).path("results")) {
            String link = r.path("url").asString();
            if (!(link.startsWith("https://") || link.startsWith("http://")) || !seen.add(link)) {
                continue;
            }
            out.add(new Result(clip(r.path("title").asString(), 200), link,
                    clip(r.path("content").asString(), 400), r.path("engine").asString()));
            if (out.size() >= count) {
                break;
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}

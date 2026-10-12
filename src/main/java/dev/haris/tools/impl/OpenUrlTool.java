package dev.haris.tools.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.tools.Args;
import dev.haris.tools.Risk;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Opens a web page or a search in the user's default browser. Only http(s), never file: or javascript:. */
@Component
public class OpenUrlTool implements Tool {

    private static final Set<String> ENGINES = Set.of("google", "youtube", "bing", "duckduckgo", "maps");

    @Override
    public String name() {
        return "open_url";
    }

    @Override
    public String description() {
        return "Open a web page in the default browser, or run a search (google, youtube, bing, duckduckgo, maps).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of(),
                Schema.str("url", "Full https:// address to open"),
                Schema.str("search", "Search terms, used instead of url"),
                Schema.oneOf("engine", "Where to search (default google)", ENGINES.stream().sorted().toArray(String[]::new)));
    }

    @Override
    public Risk risk(JsonNode input) {
        return Risk.CONFIRM;
    }

    @Override
    public boolean sendsDataOut() {
        return true;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Open in browser: " + target(input);
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        URI uri = target(input);
        Launch.uri(uri);
        return ToolOutcome.ok("Opened " + uri.getHost() + ".");
    }

    private URI target(JsonNode input) throws ToolException {
        String search = Args.optStr(input, "search", 300, "");
        if (!search.isEmpty()) {
            String engine = input.has("engine") ? Args.oneOf(input, "engine", ENGINES) : "google";
            String q = URLEncoder.encode(search, StandardCharsets.UTF_8);
            return URI.create(switch (engine) {
                case "youtube" -> "https://www.youtube.com/results?search_query=" + q;
                case "bing" -> "https://www.bing.com/search?q=" + q;
                case "duckduckgo" -> "https://duckduckgo.com/?q=" + q;
                case "maps" -> "https://www.google.com/maps/search/" + q;
                default -> "https://www.google.com/search?q=" + q;
            });
        }
        String raw = Args.str(input, "url", 2000).strip();
        if (!raw.contains("://")) {
            raw = "https://" + raw;
        }
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new ToolException("Only http(s) links can be opened.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ToolException("That link has no host.");
            }
            if (uri.getUserInfo() != null) {
                throw new ToolException("Links with embedded credentials are refused (a common phishing trick).");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ToolException("That is not a valid link.");
        }
    }
}

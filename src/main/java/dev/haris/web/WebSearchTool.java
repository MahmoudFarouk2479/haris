package dev.haris.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Web search through the local SearXNG engine. Results are outside content. */
@Component
public class WebSearchTool implements Tool {

    private static final Set<String> LANGUAGES = Set.of("en", "ar", "auto");

    private final SearchClient search;

    public WebSearchTool(SearchClient search) {
        this.search = search;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web (news, facts, prices, how-to). Returns titles, links and snippets; "
                + "use fetch_page to read a result in full.";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("query"),
                Schema.str("query", "Search terms"),
                Schema.oneOf("language", "Result language (default auto)", "ar", "auto", "en"),
                Schema.integer("count", "Number of results (default 6)", 1, 10));
    }

    @Override
    public boolean sendsDataOut() {
        return true;
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Search the web for \"" + Args.str(input, "query", 300) + "\"";
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String query = Args.str(input, "query", 300);
        String lang = input.has("language") ? Args.oneOf(input, "language", LANGUAGES) : "auto";
        int count = Args.intIn(input, "count", 1, 10, 6);
        List<SearchClient.Result> results;
        try {
            results = search.search(query, "auto".equals(lang) ? null : lang, count);
        } catch (IOException e) {
            throw new ToolException(e.getMessage());
        }
        if (results.isEmpty()) {
            return ToolOutcome.ok("No results for \"" + query + "\".");
        }
        StringBuilder out = new StringBuilder("Results for \"" + query + "\":\n");
        int i = 1;
        for (SearchClient.Result r : results) {
            out.append('\n').append(i++).append(". ").append(r.title()).append('\n')
                    .append("   ").append(r.url()).append('\n');
            if (!r.snippet().isEmpty()) {
                out.append("   ").append(r.snippet()).append('\n');
            }
        }
        return ToolOutcome.untrusted(out.toString());
    }
}

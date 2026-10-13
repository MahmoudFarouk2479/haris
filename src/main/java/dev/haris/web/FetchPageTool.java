package dev.haris.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dev.haris.tools.Args;
import dev.haris.tools.Schema;
import dev.haris.tools.Tool;
import dev.haris.tools.ToolException;
import dev.haris.tools.ToolOutcome;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Reads one public web page as plain text. Private-network addresses are unreachable. */
@Component
public class FetchPageTool implements Tool {

    private final PageFetcher fetcher;

    public FetchPageTool(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return "fetch_page";
    }

    @Override
    public boolean readsOutsideContent() {
        return true;   // the page, and any error the server provokes, are written by the site
    }

    @Override
    public String description() {
        return "Read the text of a public web page (an article, documentation, a search result).";
    }

    @Override
    public Map<String, Object> schema() {
        return Schema.object(List.of("url"), Schema.str("url", "Full https:// address"));
    }

    @Override
    public boolean sendsDataOut() {
        return true;   // the URL itself can carry data to whoever runs the site
    }

    @Override
    public String summarize(JsonNode input) throws ToolException {
        return "Read web page " + Args.str(input, "url", 2000);
    }

    @Override
    public ToolOutcome run(JsonNode input) throws ToolException {
        String url = Args.str(input, "url", 2000);
        try {
            PageFetcher.Page page = fetcher.fetch(url);
            String header = (page.title().isBlank() ? "" : "Title: " + page.title() + "\n") + "URL: " + page.url() + "\n\n";
            return ToolOutcome.untrusted(header + (page.text().isBlank() ? "(the page has no readable text)" : page.text()));
        } catch (NetGuard.BlockedException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("Could not read the page: " + e.getMessage());
        }
    }
}

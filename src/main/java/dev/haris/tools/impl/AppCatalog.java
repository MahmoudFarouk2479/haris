package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The apps in the user's Start menu (desktop and Store apps), read with a fixed PowerShell
 * query. Launching is restricted to entries from this list, by their AppID — the model can
 * name an app, but never supply a command line or a path to an executable.
 */
@Component
public class AppCatalog {

    private static final Logger log = LoggerFactory.getLogger(AppCatalog.class);
    private static final long REFRESH_MS = 10 * 60 * 1000;
    private static final String QUERY =
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress";

    public record App(String name, String appId) {}

    private final JsonMapper json;
    private volatile List<App> apps = List.of();
    private volatile long loadedAt;

    public AppCatalog(JsonMapper json) {
        this.json = json;
    }

    public synchronized List<App> all() {
        if (System.currentTimeMillis() - loadedAt > REFRESH_MS || apps.isEmpty()) {
            apps = load();
            loadedAt = System.currentTimeMillis();
        }
        return apps;
    }

    /** Exact name match first, then a unique partial match. */
    public List<App> match(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<App> all = all();
        List<App> exact = all.stream().filter(a -> a.name().equalsIgnoreCase(q)).toList();
        if (!exact.isEmpty()) {
            return exact.subList(0, 1);
        }
        return all.stream().filter(a -> a.name().toLowerCase(Locale.ROOT).contains(q)).limit(10).toList();
    }

    private List<App> load() {
        if (!Win32.isWindows()) {
            return List.of();
        }
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", QUERY)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return List.of();
            }
            JsonNode root = json.readTree(new String(out, StandardCharsets.UTF_8));
            List<App> result = new ArrayList<>();
            for (JsonNode n : root.isArray() ? root : json.createArrayNode().add(root)) {
                String name = n.path("Name").asString();
                String id = n.path("AppID").asString();
                if (!name.isBlank() && !id.isBlank()) {
                    result.add(new App(name, id));
                }
            }
            result.sort(Comparator.comparing(App::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not list Start menu apps: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}

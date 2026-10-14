package dev.haris.settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import dev.haris.voice.GeminiSpeech;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Published after every successful save; the conversation restarts so the model sees the new rules. */
    public record SettingsChanged(Settings settings) {}

    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Path file;
    private volatile Settings current;

    public SettingsService(JsonMapper json, ApplicationEventPublisher events) {
        this.json = json;
        this.events = events;
        this.file = dataDir().resolve("settings.json");
        this.current = load();
    }

    /** ~/.haris — the one place the app writes its own data. */
    public static Path dataDir() {
        return Path.of(System.getProperty("user.home"), ".haris");
    }

    public Settings get() {
        return current;
    }

    public synchronized Settings update(Settings requested) {
        Settings next = normalize(requested.withDefaults());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("settings.json.tmp");
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(next));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save settings", e);
        }
        current = next;
        events.publishEvent(new SettingsChanged(next));
        return next;
    }

    private Settings load() {
        if (!Files.exists(file)) {
            return Settings.defaults();
        }
        try {
            return normalize(json.readValue(Files.readString(file), Settings.class).withDefaults());
        } catch (Exception e) {
            log.warn("settings.json unreadable or unsafe ({}); using defaults", e.getMessage());
            return Settings.defaults();
        }
    }

    /** Validates everything that decides where data may go or which files may be touched. */
    static Settings normalize(Settings s) {
        validateEndpoints(s);
        if (!s.sttLanguage().matches("auto|ar|en") || !s.replyLanguage().matches("auto|ar|en")) {
            throw new IllegalArgumentException("Languages must be auto, ar or en.");
        }
        validateVoices(s);
        return s.withFolders(validateFolders(s.allowedFolders())).withColabBaseUrl(colabOrigin(s.colabBaseUrl()));
    }

    /** Piper voice ids, the spoken-reply engine, and the Gemini voice must be ones Haris knows. */
    static void validateVoices(Settings s) {
        if (!s.voiceEnglish().matches(Settings.VOICE_NAME) || !s.voiceArabic().matches(Settings.VOICE_NAME)) {
            throw new IllegalArgumentException("Voice names look like en_GB-alan-medium.");
        }
        if (!"piper".equals(s.ttsEngine()) && !"gemini".equals(s.ttsEngine())) {
            throw new IllegalArgumentException("Spoken replies use piper or gemini.");
        }
        if (!GeminiSpeech.VOICES.contains(s.geminiVoice())) {
            throw new IllegalArgumentException("Unknown Gemini voice: " + s.geminiVoice());
        }
    }

    // ── Folders ───────────────────────────────────────────────────────────

    /**
     * A folder can be granted only if it is a real, existing directory that is not a drive
     * root, not the whole home folder, and not inside the system, program or app-data areas.
     */
    static List<String> validateFolders(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path p;
            try {
                p = Path.of(entry.strip());
            } catch (Exception e) {
                throw new IllegalArgumentException("Not a valid folder path: " + entry);
            }
            if (!p.isAbsolute() || !Files.isDirectory(p)) {
                throw new IllegalArgumentException("Folder does not exist: " + entry);
            }
            Path real;
            try {
                real = p.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("Folder is not accessible: " + entry);
            }
            if (real.getParent() == null) {
                throw new IllegalArgumentException("A whole drive cannot be granted: " + entry);
            }
            if (isHome(real)) {
                throw new IllegalArgumentException("The whole home folder cannot be granted; pick folders inside it.");
            }
            for (Path forbidden : forbiddenRoots()) {
                if (real.startsWith(forbidden) || forbidden.startsWith(real)) {
                    throw new IllegalArgumentException("This folder is off-limits for safety: " + entry);
                }
            }
            String s = real.toString();
            if (out.stream().noneMatch(s::equalsIgnoreCase)) {
                out.add(s);
            }
        }
        if (out.size() > 10) {
            throw new IllegalArgumentException("At most 10 folders can be granted.");
        }
        return List.copyOf(out);
    }

    /** Areas no file tool may ever reach, directly or by granting a parent folder. */
    static List<Path> forbiddenRoots() {
        String home = System.getProperty("user.home");
        return Stream.of(
                        System.getenv("SystemRoot"),
                        System.getenv("ProgramFiles"),
                        System.getenv("ProgramFiles(x86)"),
                        System.getenv("ProgramData"),
                        System.getenv("APPDATA"),
                        System.getenv("LOCALAPPDATA"),
                        home == null ? null : Path.of(home, "AppData").toString(),
                        home == null ? null : Path.of(home, ".ssh").toString(),
                        dataDir().toString())
                .filter(Objects::nonNull)
                .map(Path::of)
                .map(SettingsService::realOrSelf)
                .toList();
    }

    /** The home folder itself may not be granted (it contains AppData, .ssh, browser profiles…). */
    static boolean isHome(Path real) {
        return real.equals(realOrSelf(Path.of(System.getProperty("user.home"))));
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    // ── Endpoints ─────────────────────────────────────────────────────────

    /**
     * Where the conversation is allowed to go. Local engines must really be local — a
     * "local" provider pointed at a remote host would quietly ship every message off the
     * machine. A cloud endpoint must use HTTPS so keys and messages are never sent in
     * clear text.
     */
    static void validateEndpoints(Settings s) {
        requireLoopback("Ollama", s.ollamaBaseUrl());
        requireLoopback("LM Studio", s.lmStudioBaseUrl());
        if (s.openAiBaseUrl() != null && !s.openAiBaseUrl().isBlank()) {
            URI uri = parse("OpenAI-compatible", s.openAiBaseUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopbackHost(uri.getHost())) {
                throw new IllegalArgumentException("OpenAI-compatible endpoint must use https://");
            }
        }
        if (s.colabBaseUrl() != null && !s.colabBaseUrl().isBlank()) {
            // The tunnel carries the whole conversation and the access token: HTTPS only, always.
            URI uri = parse("Colab", s.colabBaseUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("The Colab address must start with https:// (copy it from the notebook).");
            }
            if (isLoopbackHost(uri.getHost())) {
                throw new IllegalArgumentException("That is a local address; for a local Ollama choose the Ollama provider.");
            }
        }
    }

    /**
     * Colab shows full example URLs (".../api/generate"); keep only https://host[:port] so either
     * the notebook's tunnel address or Colab's own port address can be pasted as-is.
     */
    static String colabOrigin(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        URI uri = parse("Colab", url);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static void requireLoopback(String label, String url) {
        URI uri = parse(label, url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(label + " URL must be http(s)://");
        }
        if (!isLoopbackHost(uri.getHost())) {
            throw new IllegalArgumentException(label + " must run on this machine (127.0.0.1 or localhost)");
        }
    }

    private static URI parse(String label, String url) {
        try {
            URI uri = URI.create(url.strip());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " URL is not valid");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        // Only accept literal addresses here; never resolve names (no DNS tricks).
        if (!host.matches("[0-9.]+|\\[?[0-9a-fA-F:]+]?")) {
            return false;
        }
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}

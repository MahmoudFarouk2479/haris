package dev.haris.voice;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import dev.haris.system.EnginesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    /** 30 seconds of 16 kHz 16-bit mono audio. */
    public static final int MAX_AUDIO_BYTES = 30 * VoiceEngines.RATE * 2;
    private static final int MAX_SPEAK_CHARS = 1500;
    /** Raw text above this is refused before any pattern runs: cleaning only ever shortens text. */
    private static final int MAX_RAW_SPEAK_CHARS = MAX_SPEAK_CHARS * 4;
    /** Docker can take most of a minute to bring the engines up after Haris starts. */
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(90);
    /** No docker command: Haris starts the engines itself (with the right files) when Docker answers. */
    static final String STT_NOT_RUNNING = "The speech-to-text engine is not running. Start Docker Desktop and wait "
            + "until it says it is running, then close Haris and start it again: Haris starts its voice engines itself.";
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    private static final Pattern SPEAKABLE = Pattern.compile("[\\p{L}\\p{N}]");

    private final VoiceEngines engines;
    private final ColabSpeech colab;
    /** 80 ms of 16 kHz 16-bit audio, the HUD's frame size. */
    private static final int FRAME_BYTES = VoiceEngines.RATE * 2 * 80 / 1000;
    /** A frame this loud counts as speech (the HUD's own threshold is a little higher). */
    private static final double VOICED_RMS = 0.02;
    /** About a third of a second of voiced audio: the shortest real word. */
    private static final int MIN_VOICED_FRAMES = 4;
    /** How long a new recording may wait for the previous one before it is refused. */
    private static final Duration BUSY_WAIT = Duration.ofSeconds(2);

    private final Semaphore transcribing = new Semaphore(1);
    private final WakeWordDetector wakeWord;
    private final GeminiSpeech gemini;
    private final SettingsService settings;
    private final EnginesService startup;
    private volatile Map<String, Object> cachedStatus;
    private volatile long statusAt;

    public VoiceController(VoiceEngines engines, ColabSpeech colab, WakeWordDetector wakeWord, GeminiSpeech gemini,
                           SettingsService settings, EnginesService startup) {
        this.wakeWord = wakeWord;
        this.gemini = gemini;
        this.startup = startup;
        this.engines = engines;
        this.colab = colab;
        this.settings = settings;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        if (cachedStatus == null || System.currentTimeMillis() - statusAt > 5000) {
            Map<String, Object> status = new LinkedHashMap<>(engines.status());
            boolean wake = wakeWord.available();
            status.put("wake", wake);
            status.put("wakeWords", wake ? List.of(WakeWordSocket.WAKE_WORD) : List.of());
            status.put("sttRoute", colab.target().isPresent() ? "colab" : "local");
            String device = startup.sttDevice();
            if (device != null) {
                status.put("sttDevice", device);   // where the local Whisper runs: "gpu" or "cpu"
            }
            long resumeAt = gemini.pausedUntil();
            boolean paused = resumeAt > System.currentTimeMillis();
            status.put("ttsRoute", !gemini.selected() ? "local" : paused ? "gemini-paused" : "gemini");
            if (paused) {
                status.put("ttsResumeAt", resumeAt);   // e.g. a daily quota: Piper speaks until then
            }
            cachedStatus = status;
            statusAt = System.currentTimeMillis();
        }
        return cachedStatus;
    }

    /** Body: raw 16 kHz, 16-bit, mono little-endian PCM from the HUD's microphone. */
    @PostMapping(value = "/transcribe", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, String>> transcribe(@RequestBody byte[] pcm) {
        if (pcm.length < VoiceEngines.RATE / 5 || pcm.length > MAX_AUDIO_BYTES || pcm.length % 2 != 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Recording too short or too long."));
        }
        // Silence, room noise and clicks never reach the engine: on a laptop CPU each one costs
        // seconds, and Whisper "hears" words in noise anyway.
        if (!hasSpeech(pcm)) {
            return ResponseEntity.ok(Map.of("text", "", "via", "none"));
        }
        // One recording at a time. A second one is refused rather than queued, so the user is never
        // waiting behind a backlog of old recordings.
        boolean acquired;
        try {
            acquired = transcribing.tryAcquire(BUSY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Still working on the previous recording. Say it again in a moment."));
        }
        try {
            return transcribeNow(pcm);
        } finally {
            transcribing.release();
        }
    }

    /** True when at least {@link #MIN_VOICED_FRAMES} 80 ms frames are loud enough to be speech. */
    static boolean hasSpeech(byte[] pcm) {
        int voiced = 0;
        for (int off = 0; off + FRAME_BYTES <= pcm.length; off += FRAME_BYTES) {
            if (WakeWordDetector.rms(java.util.Arrays.copyOfRange(pcm, off, off + FRAME_BYTES)) >= VOICED_RMS
                    && ++voiced >= MIN_VOICED_FRAMES) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, String>> transcribeNow(byte[] pcm) {
        String lang = settings.get().sttLanguage();
        String language = "auto".equals(lang) ? null : lang;
        Optional<ColabSpeech.Target> gpu = colab.available();
        if (gpu.isPresent()) {
            try {
                return ResponseEntity.ok(Map.of("text", colab.transcribe(gpu.get(), pcm, language), "via", "colab"));
            } catch (IOException e) {
                log.warn("Colab speech-to-text failed, using the local engine: {}", e.getMessage());
            }
        }
        startup.awaitVoice(STARTUP_WAIT);
        try {
            String text = engines.transcribe(pcm, language);
            return ResponseEntity.ok(Map.of("text", text, "via", "local"));
        } catch (IOException e) {
            log.warn("Speech-to-text failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", STT_NOT_RUNNING));
        }
    }

    public record SpeakRequest(String text) {}

    @PostMapping(value = "/speak", produces = "audio/wav")
    public ResponseEntity<byte[]> speak(@RequestBody SpeakRequest request) {
        String raw = request.text();
        if (raw == null || raw.length() > MAX_RAW_SPEAK_CHARS) {
            return ResponseEntity.badRequest().build();
        }
        String text = clean(raw);
        // Nothing pronounceable (only emoji or symbols) makes Piper fail; skip it quietly.
        if (text.isEmpty() || text.length() > MAX_SPEAK_CHARS || !SPEAKABLE.matcher(text).find()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<String> geminiKey = gemini.available();
        if (geminiKey.isPresent()) {
            try {
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                        .body(gemini.speak(geminiKey.get(), text));
            } catch (IOException e) {
                log.warn("Gemini speech failed, using the local voice: {}", e.getMessage());
            }
        }
        Settings s = settings.get();
        String voice = ARABIC.matcher(text).find() ? s.voiceArabic() : s.voiceEnglish();
        startup.awaitVoice(STARTUP_WAIT);
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(engines.synthesize(text, voice));
        } catch (IOException e) {
            log.warn("Text-to-speech failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** Markdown and links read aloud sound terrible; speak the words only. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("```[\\s\\S]*?```", " (code omitted) ")
                // Links become their label before bare URLs are replaced (which would eat the ")").
                // Bounded on both sides, so a long run of "[](" cannot make the engine backtrack for minutes.
                .replaceAll("\\[([^\\]\\n]{0,200})]\\([^)\\n]{0,500}\\)", "$1")
                .replaceAll("https?://\\S+", " link ")
                .replaceAll("[*_#`>|~]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}

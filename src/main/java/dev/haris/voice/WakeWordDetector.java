package dev.haris.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Hey Haris" detection inside Haris itself, with Vosk (Apache-2.0) and its small English model
 * (Apache-2.0): no container, no network, and free to use in any kind of product.
 *
 * <p>The recogniser uses its full English vocabulary, so the name has to beat every similar word —
 * restricting the vocabulary to the name makes it hear the name in almost any sentence. The English
 * model writes "Haris" as "harris", which is also a common surname (news, TV), so the name alone is
 * not enough: it must directly follow a greeting ("hey Haris", "okay Haris", "ya Haris").
 * To keep the CPU idle in a quiet room, audio is only decoded while someone is speaking; frames are
 * processed in memory and dropped.
 */
@Component
public class WakeWordDetector {

    private static final Logger log = LoggerFactory.getLogger(WakeWordDetector.class);
    public static final String MODEL = "vosk-model-small-en-us-0.15";
    /** How the English model spells the name. */
    static final Set<String> NAMES = Set.of("haris", "harris");
    /** Words that must come right before it ("ya" and "yeah" cover the Arabic "يا حارس"). */
    static final Set<String> GREETINGS = Set.of("hey", "hi", "okay", "ok", "ya", "yeah");

    private final JsonMapper json;
    private volatile Model model;
    private volatile boolean failed;

    public WakeWordDetector(JsonMapper json) {
        this.json = json;
    }

    /** True when the model is installed (it loads on first use). */
    public boolean available() {
        return !failed && modelDir().isPresent();
    }

    /** One stream per open microphone; not thread-safe, close it with the microphone. */
    public Optional<Stream> open() {
        Model m = model();
        if (m == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Stream(new Recognizer(m, (float) VoiceEngines.RATE)));
        } catch (Exception e) {
            log.warn("Could not start wake-word recognition: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private synchronized Model model() {
        if (model == null && !failed) {
            Optional<Path> dir = modelDir();
            if (dir.isEmpty()) {
                return null;
            }
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                model = new Model(dir.get().toString());
                log.info("Wake-word model loaded from {}", dir.get());
            } catch (Throwable e) {   // a missing native library is an Error, not an Exception
                failed = true;
                log.warn("Wake word unavailable: {}", e.toString());
            }
        }
        return model;
    }

    /** Installed: next to Haris.exe (jpackage --app-content). From source: the project folder. */
    static Optional<Path> modelDir() {
        List<Path> roots = new ArrayList<>();
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null) {
            roots.add(Path.of(exe).getParent());
        }
        roots.add(Path.of("").toAbsolutePath());
        return roots.stream()
                .map(r -> r.resolve("models").resolve(MODEL))
                .filter(d -> Files.isRegularFile(d.resolve("am").resolve("final.mdl")))
                .findFirst();
    }

    @PreDestroy
    synchronized void close() {
        if (model != null) {
            model.close();
            model = null;
        }
    }

    /** True when a Vosk result (partial or final) contains a greeting immediately followed by the name. */
    static boolean mentionsWakeWord(JsonNode result) {
        String text = result.path("partial").asString(result.path("text").asString(""));
        String[] words = text.strip().split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) {
            if (GREETINGS.contains(words[i]) && NAMES.contains(words[i + 1])) {
                return true;
            }
        }
        return false;
    }

    /** Root-mean-square level of 16-bit little-endian PCM, 0..1. */
    static double rms(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int n = pcm.length / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double s = b.getShort() / 32768.0;
            sum += s * s;
        }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    public final class Stream implements AutoCloseable {

        private static final double VOICE_RMS = 0.012;   // a little under the HUD's speech level
        private static final int PRE_ROLL_FRAMES = 4;     // keep the start of the word
        private static final long HANG_MS = 1200;         // keep decoding through short pauses

        private final Recognizer recognizer;
        private final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
        private boolean active;
        private long lastVoice;

        private Stream(Recognizer recognizer) {
            this.recognizer = recognizer;
        }

        /** Feeds one frame of 16 kHz 16-bit mono PCM; true when the wake word was just heard. */
        public boolean accept(byte[] pcm) {
            long now = System.currentTimeMillis();
            if (rms(pcm) >= VOICE_RMS) {
                lastVoice = now;
                if (!active) {
                    active = true;
                    for (byte[] f : preRoll) {
                        if (feed(f)) {
                            return wake();
                        }
                    }
                    preRoll.clear();
                }
            }
            if (!active) {
                preRoll.addLast(pcm);
                if (preRoll.size() > PRE_ROLL_FRAMES) {
                    preRoll.removeFirst();
                }
                return false;
            }
            if (feed(pcm)) {
                return wake();
            }
            if (now - lastVoice > HANG_MS) {   // the speaker stopped: start fresh next time
                active = false;
                recognizer.reset();
            }
            return false;
        }

        private boolean feed(byte[] pcm) {
            boolean end = recognizer.acceptWaveForm(pcm, pcm.length);
            String result = end ? recognizer.getResult() : recognizer.getPartialResult();
            try {
                return mentionsWakeWord(json.readTree(result));
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean wake() {
            recognizer.reset();
            active = false;
            preRoll.clear();
            return true;
        }

        @Override
        public void close() {
            recognizer.close();
        }
    }
}

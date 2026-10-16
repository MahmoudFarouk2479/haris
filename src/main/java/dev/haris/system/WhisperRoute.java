package dev.haris.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides where speech-to-text runs — the NVIDIA GPU or the CPU — and falls back to the CPU when
 * the GPU container does not come up. Both answer on the same Wyoming port, so nothing else in
 * Haris needs to know. The Docker and nvidia-smi calls are behind {@link Host} and {@link Engines}
 * so the decisions can be tested without either.
 */
final class WhisperRoute {

    private static final Logger log = LoggerFactory.getLogger(WhisperRoute.class);

    /** The switch that forces the CPU (environment variable, or a line in voice/.env). */
    static final String SWITCH = "HARIS_WHISPER_GPU";
    private static final Set<String> NO = Set.of("false", "0", "no", "off", "cpu");

    enum Device {
        GPU, CPU;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a Whisper container came out of its start. */
    enum Outcome {
        /** Answering (or, on the CPU, simply running). */
        READY,
        /** Exited, never answered, or could not be created. */
        FAILED,
        /** Exited because an earlier, interrupted download left a broken model in its volume. */
        MODEL_BROKEN
    }

    /** What the machine offers. */
    interface Host {
        /** {@code docker info --format "{{json .Runtimes}}"}, or "" if Docker did not answer. */
        String dockerRuntimes();

        /** True when {@code nvidia-smi} runs on the host and lists a GPU. */
        boolean nvidiaSmi();
    }

    /** Starting and checking the Whisper container. */
    interface Engines {
        /** {@code docker compose up -d} for the voice project with this device's files. */
        boolean up(Device device);

        /** Waits for the Whisper container started for this device to answer, or to fail. */
        Outcome await(Device device);

        /** Removes the Whisper container and this device's model volume (it holds only the download). */
        void dropModel(Device device);

        /** Whisper on this device has been started in place of the other one (the fallback). */
        default void started(Device device) {
        }
    }

    /** The local Docker image store. */
    interface Images {
        /** True when the image is already on this machine. */
        boolean present(String image);

        /** Starts downloading the image on a background thread and returns at once. */
        void pullInBackground(String image);
    }

    /** What {@code docker inspect -f {{.State.Status}}} says about the Whisper container. */
    enum State {
        /** Running, or about to. */
        UP,
        /** Exited, dead, or gone. */
        GONE,
        /** Docker did not answer in time: ask again. */
        UNKNOWN
    }

    private WhisperRoute() {
    }

    /** False only when the switch says so; the environment wins over voice/.env. Unset means "use the GPU if there is one". */
    static boolean gpuAllowed(String fromEnvironment, String fromDotEnv) {
        String value = fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment : fromDotEnv;
        return value == null || !NO.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /** The GPU when it is allowed, Docker has the NVIDIA runtime, and the host's NVIDIA driver answers. */
    static Device choose(boolean gpuAllowed, Host host) {
        if (!gpuAllowed) {
            return Device.CPU;
        }
        String runtimes = host.dockerRuntimes();
        if (runtimes == null || !runtimes.contains("\"nvidia\"")) {
            return Device.CPU;
        }
        return host.nvidiaSmi() ? Device.GPU : Device.CPU;
    }

    /**
     * After {@code up}: waits for Whisper on {@code device}, repairs a broken model download once,
     * and falls back from the GPU to the CPU if the GPU container does not answer. Returns the device
     * Whisper ends up on.
     */
    static Device settle(Device device, Engines engines) {
        Outcome outcome = awaitHealing(device, engines);
        if (outcome == Outcome.READY || device == Device.CPU) {
            return device;
        }
        log.warn("Speech-to-text did not start on the GPU; using the CPU instead.");
        return fallBack(engines);
    }

    /** Starts the CPU Whisper in place of the GPU one (same project, so it replaces the container). */
    static Device fallBack(Engines engines) {
        if (engines.up(Device.CPU)) {
            engines.started(Device.CPU);   // the status shows the CPU from now, not after the CPU watch
            awaitHealing(Device.CPU, engines);
        }
        return Device.CPU;
    }

    /**
     * The GPU image is several GB. Pulling it inside the blocking {@code compose up} would hold up all
     * voice (Piper is in the same project) on the first launch, and a slow link could cancel it on every
     * launch. So when it is not on this machine yet, Whisper starts on the CPU now and the image is
     * downloaded in the background; the GPU is used from the next start.
     */
    static Device withImage(Device device, String gpuImage, Images images) {
        if (device != Device.GPU) {
            return device;
        }
        if (gpuImage == null) {
            log.warn("compose.gpu.yaml names no image; speech-to-text stays on the CPU.");
            return Device.CPU;
        }
        if (images.present(gpuImage)) {
            return Device.GPU;
        }
        log.info("The GPU speech-to-text image is not downloaded yet: using the CPU now and downloading it in the background.");
        images.pullInBackground(gpuImage);
        return Device.CPU;
    }

    private static final Pattern IMAGE = Pattern.compile("^\\s*image:\\s*[\"']?([^\"'\\s#]+)");

    /** The first {@code image:} of a compose file (the GPU override has one service), or null. */
    static String imageOf(Path composeFile) {
        try {
            for (String line : Files.readAllLines(composeFile, StandardCharsets.UTF_8)) {
                Matcher m = IMAGE.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Turns {@code docker pull}'s per-layer lines into "3 of 12 layers downloaded" (null when nothing changed). */
    static final class PullProgress {
        private final Set<String> layers = new HashSet<>();
        private final Set<String> done = new HashSet<>();

        String read(String line) {
            int colon = line.indexOf(": ");
            if (colon <= 0) {
                return null;
            }
            String id = line.substring(0, colon).strip();
            String status = line.substring(colon + 2).strip();
            switch (status) {
                case "Already exists" -> {
                    layers.add(id);
                    done.add(id);
                    return null;
                }
                case "Pulling fs layer", "Waiting" -> {
                    layers.add(id);
                    return null;
                }
                case "Download complete" -> {
                    layers.add(id);
                    return done.add(id) ? done.size() + " of " + layers.size() + " layers downloaded" : null;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    /** Reads {@code docker inspect -f {{.State.Status}}}: "" means Docker did not answer, not that Whisper exited. */
    static State containerState(String inspect) {
        String s = inspect == null ? "" : inspect.strip();
        if (s.isEmpty()) {
            return State.UNKNOWN;
        }
        return s.equals("running") || s.equals("created") || s.equals("restarting") ? State.UP : State.GONE;
    }

    /**
     * The oldest Docker Compose that can apply compose.gpu.yaml: it replaces the CPU model volume with
     * {@code volumes: !override [...]}, a merge tag Compose understands from 2.24.4. An older Compose
     * cannot start the GPU Whisper as written (it would keep both volumes on /data), so the GPU path,
     * and the several-GB image download for it, are skipped there.
     */
    static final String MIN_COMPOSE = "2.24.4";

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * True when {@code docker compose version --short} (for example "2.29.7-desktop.1", "v2.24.4" or
     * "5.6.0") names a Compose at least {@link #MIN_COMPOSE}. No answer, or none that reads as a version, is false.
     */
    static boolean composeCanApplyGpuFile(String version) {
        int[] have = parseVersion(version);
        return have != null && compareVersions(have, parseVersion(MIN_COMPOSE)) >= 0;
    }

    /** The first "major.minor.patch" in the text, or null. */
    static int[] parseVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VERSION.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (NumberFormatException e) {
            return null;   // a part too long to be a version number
        }
    }

    /** Negative, zero or positive as version {@code a} is older than, the same as, or newer than {@code b}. */
    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** After the GPU failed, Haris starts on the CPU for this many days (or until the GPU image changes). */
    static final int RETRY_GPU_DAYS = 3;

    /** What is written to the data directory when the GPU Whisper did not start. */
    static String failureMarker(String gpuImage, LocalDate day) {
        return day + " " + gpuImage;
    }

    /** True while a failure recorded with {@link #failureMarker} still applies to this image. */
    static boolean gpuRecentlyFailed(String marker, String gpuImage, LocalDate today) {
        if (marker == null || gpuImage == null) {
            return false;
        }
        String[] parts = marker.strip().split(" ", 2);
        if (parts.length != 2 || !parts[1].equals(gpuImage)) {
            return false;
        }
        try {
            return today.isBefore(LocalDate.parse(parts[0]).plusDays(RETRY_GPU_DAYS));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Outcome awaitHealing(Device device, Engines engines) {
        Outcome outcome = engines.await(device);
        if (outcome == Outcome.MODEL_BROKEN) {
            log.warn("Whisper's model download was interrupted earlier; downloading it again.");
            engines.dropModel(device);
            outcome = engines.up(device) ? engines.await(device) : Outcome.FAILED;
        }
        return outcome;
    }

    /**
     * The value of {@code key} in a docker-compose style .env file, or null. As in Compose, a quoted
     * value is taken whole, and an unquoted one ends at an inline comment (" #").
     */
    static String dotEnv(Path file, String key) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.startsWith(key + "=")) {
                    String v = l.substring(key.length() + 1).strip();
                    if (!v.isEmpty() && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                        int end = v.indexOf(v.charAt(0), 1);
                        return end > 0 ? v.substring(1, end) : v.substring(1);
                    }
                    int comment = v.indexOf(" #");
                    return (comment >= 0 ? v.substring(0, comment) : v).strip();
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}

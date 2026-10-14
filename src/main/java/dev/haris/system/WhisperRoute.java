package dev.haris.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

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
            awaitHealing(Device.CPU, engines);
        }
        return Device.CPU;
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

    /** The value of {@code key} in a docker-compose style .env file, or null. */
    static String dotEnv(Path file, String key) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.startsWith(key + "=")) {
                    return l.substring(key.length() + 1).strip().replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}

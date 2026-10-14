package dev.haris.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.haris.security.SecretStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts the local voice and search containers with Haris and stops them when it exits, so no
 * microphone or search engine keeps running while the assistant is closed. Commands are fixed
 * argument lists for the Docker CLI; nothing is passed through a shell.
 */
@Component
public class EnginesService {

    private static final Logger log = LoggerFactory.getLogger(EnginesService.class);

    private final SecretStore secrets;
    private final boolean enabled;
    private final boolean stopOnExit;
    private final List<Path> started = new ArrayList<>();
    /** Opens once the voice engines have been started (or will not be): speech waits on it. */
    private final CountDownLatch voiceSettled = new CountDownLatch(1);

    public EnginesService(SecretStore secrets,
                          @Value("${haris.engines.autostart:true}") boolean enabled,
                          @Value("${haris.engines.stop-on-exit:true}") boolean stopOnExit) {
        this.secrets = secrets;
        this.enabled = enabled;
        this.stopOnExit = stopOnExit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            voiceSettled.countDown();
            return;
        }
        Thread.ofVirtual().name("engines").start(() -> {
            try {
                // Docker Desktop can take a minute to answer after it (or Windows) starts: keep asking
                // for about three minutes before giving up, instead of once.
                if (!waitFor(EnginesService::dockerRunning, DOCKER_ATTEMPTS, DOCKER_RETRY_MS)) {
                    log.info("Docker is not running: voice and web search are off until it is started.");
                    return;
                }
                for (String name : List.of("voice", "search")) {
                    composeFile(name).ifPresentOrElse(file -> {
                        if (compose(file, "up", "-d")) {
                            synchronized (started) {
                                started.add(file);
                            }
                            log.info("Started local {} engines.", name);
                            if (name.equals("voice")) {
                                Thread.ofVirtual().name("whisper-check").start(() -> healWhisper(file));
                            }
                        }
                    }, () -> log.info("No {}/compose.yaml found; {} engines not started.", name, name));
                    if (name.equals("voice")) {
                        voiceSettled.countDown();
                    }
                }
            } finally {
                voiceSettled.countDown();
            }
        });
    }

    /**
     * Waits (up to {@code max}) while Haris is still starting the voice engines, so the first words
     * after launch are spoken late rather than lost. Returns at once when start-up has finished.
     */
    public void awaitVoice(Duration max) {
        try {
            voiceSettled.await(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        if (!stopOnExit || !enabled) {
            return;
        }
        // Stop both projects even if start-up had not finished: "stop" is harmless when nothing runs,
        // and a half-finished "up" must not leave a microphone engine running after Haris exits.
        for (String name : List.of("voice", "search")) {
            composeFile(name).ifPresent(file -> {
                if (compose(file, "stop")) {
                    log.info("Stopped local {} engines.", name);
                }
            });
        }
    }

    private boolean compose(Path file, String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", file.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(file.getParent().toFile())
                .redirectErrorStream(true);
        // SearXNG's secret comes from the OS vault, so it is never written to a file.
        pb.environment().put("SEARXNG_SECRET", secrets.getOrCreate(SecretStore.SEARXNG_SECRET));
        try {
            Process p = pb.start();
            // Drain output on a side thread (a full pipe would stall docker), keep the tail for the log.
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    String all = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    synchronized (output) {
                        output.append(all.length() > 2000 ? all.substring(all.length() - 2000) : all);
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                log.warn("docker compose {} {} timed out", String.join(" ", args), file);
                return false;
            }
            reader.join(2000);
            if (p.exitValue() != 0) {
                synchronized (output) {
                    log.warn("docker compose {} failed for {}:\n{}", String.join(" ", args), file, output.toString().strip());
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not run docker: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * If JARVIS is closed while Whisper is still downloading its model (the first start), the
     * volume keeps a half-written model and Whisper exits on every later start ("Unable to open
     * file 'model.bin'"). Detect exactly that, drop the model volume (it holds nothing but the
     * download) and start again, so the model downloads cleanly.
     */
    private void healWhisper(Path file) {
        String container = "haris-voice-whisper-1";
        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String state = docker("inspect", "-f", "{{.State.Status}}", container).strip();
            if (state.equals("running")) {
                continue;
            }
            if (state.equals("exited") && docker("logs", "--tail", "30", container).contains("Unable to open file 'model.bin'")) {
                log.warn("Whisper's model download was interrupted earlier; downloading it again.");
                docker("rm", "-f", container);
                docker("volume", "rm", "haris-voice_whisper-data");
                compose(file, "up", "-d", "whisper");
            }
            return;
        }
    }

    /** Runs one Docker CLI command (fixed arguments, no shell) and returns its output, or "" on failure. */
    private static String docker(String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return p.waitFor(30, TimeUnit.SECONDS) ? out : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static final int DOCKER_ATTEMPTS = 12;
    private static final long DOCKER_RETRY_MS = 10_000;

    /** Asks {@code ready} up to {@code attempts} times, pausing between tries; true once it answers yes. */
    static boolean waitFor(java.util.function.BooleanSupplier ready, int attempts, long pauseMs) {
        for (int i = 1; i <= attempts; i++) {
            if (ready.getAsBoolean()) {
                return true;
            }
            if (i == 1) {
                log.info("Waiting for Docker to answer...");
            }
            if (i < attempts) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean dockerRunning() {
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Installed: next to the app (jpackage --app-content). From source: the project folder. */
    private static Optional<Path> composeFile(String name) {
        List<Path> roots = new ArrayList<>();
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null) {
            roots.add(Path.of(exe).getParent());   // jpackage --app-content lands next to Haris.exe
        }
        roots.add(Path.of("").toAbsolutePath());
        return roots.stream().map(r -> r.resolve(name).resolve("compose.yaml")).filter(Files::isRegularFile).findFirst();
    }
}

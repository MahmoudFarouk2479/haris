package dev.haris.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import dev.haris.security.SecretStore;
import dev.haris.settings.SettingsService;
import dev.haris.system.WhisperRoute.Device;
import dev.haris.system.WhisperRoute.Outcome;
import dev.haris.voice.VoiceEngines;
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
    private final VoiceEngines voice;
    private final String gpuSwitch;
    private final boolean enabled;
    private final boolean stopOnExit;
    private final List<Path> started = new ArrayList<>();
    /** Opens once the voice engines have been started (or will not be): speech waits on it. */
    private final CountDownLatch voiceSettled = new CountDownLatch(1);
    /** Where speech-to-text runs, or null until the voice engines have started. */
    private volatile Device sttDevice;
    /** Set when Haris exits, so a fallback still in progress does not start containers again. */
    private volatile boolean stopping;
    /** Held around each voice {@code up} and the exit's {@code stop}, so an {@code up} cannot finish after the stop. */
    private final ReentrantLock voiceLock = new ReentrantLock();
    /** The background download of the GPU image, if one is running. */
    private volatile Process pull;
    /** Starts the {@code docker pull} process; a test stands in for it. */
    ProcessStarter pullStarter = ProcessBuilder::start;
    private final AtomicBoolean pulling = new AtomicBoolean();
    /** Written when the GPU Whisper did not start, so later launches go straight to the CPU for a while. */
    private final Path gpuFailedFile = SettingsService.dataDir().resolve("whisper-gpu-failed");

    public EnginesService(SecretStore secrets, VoiceEngines voice,
                          @Value("${HARIS_WHISPER_GPU:}") String gpuSwitch,
                          @Value("${haris.engines.autostart:true}") boolean enabled,
                          @Value("${haris.engines.stop-on-exit:true}") boolean stopOnExit) {
        this.secrets = secrets;
        this.voice = voice;
        this.gpuSwitch = gpuSwitch;
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
                        if (name.equals("voice")) {
                            startVoice(file);
                        } else if (compose(file, "up", "-d")) {
                            synchronized (started) {
                                started.add(file);
                            }
                            log.info("Started local {} engines.", name);
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
     * Starts Whisper and Piper, with Whisper on the GPU when there is one (see {@link WhisperRoute}).
     * Piper is up as soon as this returns; whether the GPU Whisper answers is checked on a side thread,
     * which falls back to the CPU Whisper if it does not. A GPU image that is not downloaded yet never
     * holds this up: Whisper starts on the CPU and the image is pulled in the background (see
     * {@link WhisperRoute#withImage}); a GPU that failed recently is skipped for a few days.
     */
    private void startVoice(Path file) {
        boolean allowed = WhisperRoute.gpuAllowed(gpuSwitch,
                WhisperRoute.dotEnv(file.resolveSibling(".env"), WhisperRoute.SWITCH));
        Device device = WhisperRoute.choose(allowed, HOST);
        String gpuImage = null;
        if (device == Device.GPU && !Files.isRegularFile(gpuFile(file))) {
            device = Device.CPU;
        }
        if (device == Device.GPU) {
            gpuImage = WhisperRoute.imageOf(gpuFile(file));
            if (gpuImage != null && WhisperRoute.gpuRecentlyFailed(read(gpuFailedFile), gpuImage, LocalDate.now())) {
                log.info("Speech-to-text did not start on the GPU recently; using the CPU for now "
                        + "(delete {} to try the GPU again).", gpuFailedFile);
                device = Device.CPU;
            } else {
                device = WhisperRoute.withImage(device, gpuImage, images(file));
            }
        }
        log.info("Speech-to-text device: {}{}", device.label(), allowed ? "" : " (" + WhisperRoute.SWITCH + " is off)");
        WhisperRoute.Engines whisper = whisperEngines(file);
        boolean up = whisper.up(device);
        if (!up && device == Device.GPU && !stopping) {
            log.warn("Could not start the voice engines with the GPU; trying the CPU.");
            device = Device.CPU;
            up = whisper.up(device);
        }
        if (!up) {
            return;
        }
        synchronized (started) {
            started.add(file);
        }
        sttDevice = device;
        log.info("Started local voice engines.");
        Device chosen = device;
        String image = gpuImage;
        Thread.ofVirtual().name("whisper-check").start(() -> {
            Device settled = WhisperRoute.settle(chosen, whisper);
            sttDevice = settled;
            log.info("Speech-to-text is running on the {}.", settled.label());
            if (chosen == Device.GPU && !stopping) {
                rememberGpu(settled == Device.GPU, image);
            }
        });
    }

    /** Records (or clears) a GPU failure, so a GPU that cannot run Whisper is not retried on every launch. */
    private void rememberGpu(boolean worked, String gpuImage) {
        try {
            if (worked) {
                Files.deleteIfExists(gpuFailedFile);
            } else {
                Files.createDirectories(gpuFailedFile.getParent());
                Files.writeString(gpuFailedFile, WhisperRoute.failureMarker(gpuImage, LocalDate.now()));
            }
        } catch (IOException e) {
            log.debug("Could not record the GPU outcome: {}", e.getMessage());
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Longest a background download of the GPU image may take before it is left for the next start. */
    private static final long PULL_MAX_MS = 3 * 60 * 60_000L;

    /** The local image store; a missing GPU image is pulled on a side thread, never inside {@code compose up}. */
    private WhisperRoute.Images images(Path file) {
        return new WhisperRoute.Images() {
            @Override
            public boolean present(String image) {
                return run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image), 30_000).exit() == 0;
            }

            @Override
            public void pullInBackground(String image) {
                if (pulling.compareAndSet(false, true)) {
                    Thread.ofVirtual().name("whisper-gpu-pull").start(() -> {
                        try {
                            pullImage(file, image);
                        } finally {
                            pulling.set(false);
                        }
                    });
                }
            }
        };
    }

    /**
     * {@code docker pull} of the GPU image, logging progress. One attempt per launch, no retry loop:
     * layers that finished are kept by Docker, so a download cut short (Haris closed, link dropped)
     * carries on from there at the next start. Haris switches to the GPU at the next start, not
     * mid-session, so speech-to-text is never taken away while the user may be talking.
     */
    void pullImage(Path file, String image) {
        if (stopping) {
            return;
        }
        long start = System.currentTimeMillis();
        log.info("Downloading the GPU speech-to-text image {} in the background (several GB, one time)...", image);
        try {
            Process p = pullStarter.start(new ProcessBuilder("docker", "pull", image)
                    .directory(file.getParent().toFile())
                    .redirectErrorStream(true));
            pull = p;
            // stop() may have run after the check above but before the process was stored, and then
            // found nothing to end: check again, so the docker pull never outlives Haris.
            if (stopping) {
                p.destroy();
                log.info("GPU image download paused because Haris is closing; it continues at the next start.");
                return;
            }
            WhisperRoute.PullProgress progress = new WhisperRoute.PullProgress();
            StringBuilder last = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null; ) {
                        String step = progress.read(line);
                        if (step != null) {
                            log.info("GPU speech-to-text image: {}.", step);
                        }
                        synchronized (last) {
                            last.setLength(0);
                            last.append(line);
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            boolean finished = p.waitFor(PULL_MAX_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
            }
            reader.join(2000);
            long minutes = (System.currentTimeMillis() - start) / 60_000;
            if (stopping) {
                log.info("GPU image download paused because Haris is closing; it continues at the next start.");
            } else if (!finished) {
                log.warn("GPU image download did not finish in {} min; it continues at the next start.", minutes);
            } else if (p.exitValue() == 0) {
                log.info("GPU speech-to-text image downloaded in {} min; Haris uses the GPU from the next start.", minutes);
            } else {
                synchronized (last) {
                    log.warn("GPU image download failed ({}); it is tried again at the next start.", last.toString().strip());
                }
            }
        } catch (IOException e) {
            log.warn("Could not run docker pull: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pull = null;
        }
    }

    /** "gpu" or "cpu": where speech-to-text runs; null before the voice engines have started. */
    public String sttDevice() {
        Device d = sttDevice;
        return d == null ? null : d.label();
    }

    private static Path gpuFile(Path file) {
        return file.resolveSibling("compose.gpu.yaml");
    }

    private static final String WHISPER_CONTAINER = "haris-voice-whisper-1";
    /** The GPU container gets as long as the HUD waits for the engines; a first model download, longer. */
    private static final long GPU_ANSWER_MS = 90_000;
    private static final long DOWNLOAD_MS = 15 * 60_000;
    /** The CPU container is only watched for an early exit, as before. */
    private static final long CPU_WATCH_MS = 30_000;
    /** How many unanswered {@code docker inspect} calls in a row are tolerated before giving up. */
    private static final int MAX_UNKNOWN = 3;

    private WhisperRoute.Engines whisperEngines(Path file) {
        return new WhisperRoute.Engines() {
            @Override
            public boolean up(Device device) {
                voiceLock.lock();
                try {
                    if (stopping) {
                        return false;
                    }
                    List<Path> files = device == Device.GPU ? List.of(file, gpuFile(file)) : List.of(file);
                    return compose(files, "up", "-d");
                } finally {
                    voiceLock.unlock();
                }
            }

            @Override
            public void started(Device device) {
                sttDevice = device;
            }

            @Override
            public Outcome await(Device device) {
                return awaitWhisper(device);
            }

            @Override
            public void dropModel(Device device) {
                docker("rm", "-f", WHISPER_CONTAINER);
                docker("volume", "rm", device == Device.GPU ? "haris-voice_whisper-gpu-data" : "haris-voice_whisper-data");
            }
        };
    }

    /**
     * If Haris is closed while Whisper is still downloading its model (the first start), the volume
     * keeps a half-written model and Whisper exits on every later start ("Unable to open file
     * 'model.bin'"): that is reported as {@link Outcome#MODEL_BROKEN} so the volume can be dropped.
     * On the GPU, Whisper must also answer within {@link #GPU_ANSWER_MS} (longer while it downloads).
     */
    private Outcome awaitWhisper(Device device) {
        long start = System.currentTimeMillis();
        int unknown = 0;
        for (;;) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.FAILED;
            }
            if (stopping) {
                return Outcome.FAILED;
            }
            long elapsed = System.currentTimeMillis() - start;
            WhisperRoute.State state = WhisperRoute.containerState(docker("inspect", "-f", "{{.State.Status}}", WHISPER_CONTAINER));
            if (state == WhisperRoute.State.UNKNOWN && ++unknown <= MAX_UNKNOWN) {
                continue;   // Docker was slow to answer: that is not an exit, ask again
            }
            if (state != WhisperRoute.State.UP) {
                String logs = docker("logs", "--tail", "30", WHISPER_CONTAINER);
                return logs.contains("Unable to open file 'model.bin'") ? Outcome.MODEL_BROKEN : Outcome.FAILED;
            }
            unknown = 0;
            if (device == Device.CPU) {
                if (elapsed >= CPU_WATCH_MS) {
                    return Outcome.READY;
                }
                continue;
            }
            if (voice.sttReady()) {
                return Outcome.READY;
            }
            if (elapsed >= GPU_ANSWER_MS && (elapsed >= DOWNLOAD_MS || !downloading())) {
                return Outcome.FAILED;
            }
        }
    }

    /** True while the GPU Whisper is still fetching its model (it logs each request to Hugging Face). */
    private static boolean downloading() {
        String logs = docker("logs", "--tail", "5", WHISPER_CONTAINER);
        return logs.contains("huggingface.co") && !logs.contains("Ready");
    }

    /** Docker and the NVIDIA driver, as {@link WhisperRoute} sees them. */
    private static final WhisperRoute.Host HOST = new WhisperRoute.Host() {
        @Override
        public String dockerRuntimes() {
            return docker("info", "--format", "{{json .Runtimes}}");
        }

        @Override
        public boolean nvidiaSmi() {
            Run r = run(List.of("nvidia-smi", "-L"), 15_000);
            return r.exit() == 0 && r.out().contains("GPU");
        }
    };

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
        stopping = true;
        Process p = pull;
        if (p != null) {
            p.destroy();   // the docker CLI only; layers already downloaded stay for the next start
        }
        if (!stopOnExit || !enabled) {
            return;
        }
        // Stop both projects even if start-up had not finished: "stop" is harmless when nothing runs,
        // and a half-finished "up" must not leave a microphone engine running after Haris exits, so the
        // voice stop waits (a while) for an "up" in progress to return first.
        for (String name : List.of("voice", "search")) {
            composeFile(name).ifPresent(file -> {
                boolean locked = false;
                if (name.equals("voice")) {
                    try {
                        locked = voiceLock.tryLock(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                try {
                    if (compose(file, "stop")) {
                        log.info("Stopped local {} engines.", name);
                    }
                } finally {
                    if (locked) {
                        voiceLock.unlock();
                    }
                }
            });
        }
    }

    /** How long Haris's exit waits for a voice {@code up} in progress before stopping anyway. */
    private static final long STOP_WAIT_SECONDS = 60;

    private boolean compose(Path file, String... args) {
        return compose(List.of(file), args);
    }

    private boolean compose(List<Path> files, String... args) {
        Path file = files.getFirst();
        List<String> cmd = new ArrayList<>(List.of("docker", "compose"));
        for (Path f : files) {
            cmd.addAll(List.of("-f", f.toString()));
        }
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
                log.warn("docker compose {} {} timed out", String.join(" ", args), files);
                return false;
            }
            reader.join(2000);
            if (p.exitValue() != 0) {
                synchronized (output) {
                    log.warn("docker compose {} failed for {}:\n{}", String.join(" ", args), files, output.toString().strip());
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

    /** Runs one Docker CLI command (fixed arguments, no shell) and returns its output, or "" on failure. */
    private static String docker(String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Run r = run(cmd, 30_000);
        return r.exit() < 0 ? "" : r.out();
    }

    /** Starts a process from a builder ({@link ProcessBuilder#start} outside tests). */
    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    /** A finished command: its exit code (-1 if it could not run or timed out) and its output. */
    record Run(int exit, String out) {
    }

    /**
     * Runs a command (fixed arguments, no shell). The output is read on a side thread, so a command that
     * hangs (a wedged nvidia-smi, a stuck docker) is bounded by the timeout and then ended.
     */
    static Run run(List<String> cmd, long timeoutMs) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new Run(-1, "");
        }
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                String all = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (out) {
                    out.append(all);
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Run(-1, "");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Run(-1, "");
        }
        synchronized (out) {
            return new Run(p.exitValue(), out.toString());
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

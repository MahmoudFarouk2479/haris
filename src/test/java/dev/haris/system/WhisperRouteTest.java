package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import dev.haris.system.WhisperRoute.Device;
import dev.haris.system.WhisperRoute.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Speech-to-text goes to the GPU only when it can work there, and falls back to the CPU when it does not. */
class WhisperRouteTest {

    private static final String WITH_NVIDIA = "{\"io.containerd.runc.v2\":{\"path\":\"runc\"},\"nvidia\":{\"path\":\"nvidia-container-runtime\"},\"runc\":{\"path\":\"runc\"}}";
    private static final String WITHOUT_NVIDIA = "{\"io.containerd.runc.v2\":{\"path\":\"runc\"},\"runc\":{\"path\":\"runc\"}}";

    private static WhisperRoute.Host host(String runtimes, boolean smi) {
        return new WhisperRoute.Host() {
            @Override
            public String dockerRuntimes() {
                return runtimes;
            }

            @Override
            public boolean nvidiaSmi() {
                return smi;
            }
        };
    }

    @Test
    void usesTheGpuWhenDockerHasTheNvidiaRuntimeAndTheDriverAnswers() {
        assertThat(WhisperRoute.choose(true, host(WITH_NVIDIA, true))).isEqualTo(Device.GPU);
    }

    @Test
    void usesTheCpuWithoutTheNvidiaRuntime() {
        assertThat(WhisperRoute.choose(true, host(WITHOUT_NVIDIA, true))).isEqualTo(Device.CPU);
        assertThat(WhisperRoute.choose(true, host("", true))).isEqualTo(Device.CPU);
        assertThat(WhisperRoute.choose(true, host(null, true))).isEqualTo(Device.CPU);
    }

    @Test
    void usesTheCpuWhenNvidiaSmiFails() {
        assertThat(WhisperRoute.choose(true, host(WITH_NVIDIA, false))).isEqualTo(Device.CPU);
    }

    @Test
    void theSwitchForcesTheCpuWithoutAskingTheHost() {
        WhisperRoute.Host never = new WhisperRoute.Host() {
            @Override
            public String dockerRuntimes() {
                throw new AssertionError("must not ask Docker");
            }

            @Override
            public boolean nvidiaSmi() {
                throw new AssertionError("must not run nvidia-smi");
            }
        };
        assertThat(WhisperRoute.choose(false, never)).isEqualTo(Device.CPU);
    }

    @Test
    void theSwitchIsOnUnlessSetToAnOffValue() {
        assertThat(WhisperRoute.gpuAllowed(null, null)).isTrue();
        assertThat(WhisperRoute.gpuAllowed("", "")).isTrue();
        assertThat(WhisperRoute.gpuAllowed("true", null)).isTrue();
        for (String off : List.of("false", "FALSE", " 0 ", "no", "off", "cpu")) {
            assertThat(WhisperRoute.gpuAllowed(off, null)).as(off).isFalse();
        }
    }

    @Test
    void theEnvironmentWinsOverVoiceDotEnv() {
        assertThat(WhisperRoute.gpuAllowed(null, "false")).isFalse();
        assertThat(WhisperRoute.gpuAllowed("", "false")).isFalse();
        assertThat(WhisperRoute.gpuAllowed("true", "false")).isTrue();
        assertThat(WhisperRoute.gpuAllowed("false", "true")).isFalse();
    }

    @Test
    void readsTheSwitchFromVoiceDotEnv(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\nWHISPER_MODEL=small-int8\nHARIS_WHISPER_GPU=\"false\"\n");
        assertThat(WhisperRoute.dotEnv(env, WhisperRoute.SWITCH)).isEqualTo("false");
        assertThat(WhisperRoute.dotEnv(env, "PIPER_VOICE")).isNull();
        assertThat(WhisperRoute.dotEnv(dir.resolve("missing"), WhisperRoute.SWITCH)).isNull();
    }

    /** Scripted engines: each await returns the next outcome queued for that device. */
    private static final class FakeEngines implements WhisperRoute.Engines {
        final Map<Device, Deque<Outcome>> outcomes = Map.of(Device.GPU, new ArrayDeque<>(), Device.CPU, new ArrayDeque<>());
        final List<String> calls = new ArrayList<>();
        boolean upWorks = true;

        @Override
        public void started(Device device) {
            calls.add("running " + device);
        }

        FakeEngines then(Device d, Outcome... os) {
            outcomes.get(d).addAll(List.of(os));
            return this;
        }

        @Override
        public boolean up(Device device) {
            calls.add("up " + device);
            return upWorks;
        }

        @Override
        public Outcome await(Device device) {
            calls.add("await " + device);
            Outcome o = outcomes.get(device).poll();
            return o == null ? Outcome.READY : o;
        }

        @Override
        public void dropModel(Device device) {
            calls.add("drop " + device);
        }
    }

    @Test
    void staysOnTheGpuWhenItAnswers() {
        FakeEngines e = new FakeEngines().then(Device.GPU, Outcome.READY);
        assertThat(WhisperRoute.settle(Device.GPU, e)).isEqualTo(Device.GPU);
        assertThat(e.calls).containsExactly("await GPU");
    }

    @Test
    void fallsBackToTheCpuWhenTheGpuContainerFails() {
        FakeEngines e = new FakeEngines().then(Device.GPU, Outcome.FAILED);
        assertThat(WhisperRoute.settle(Device.GPU, e)).isEqualTo(Device.CPU);
        assertThat(e.calls).containsExactly("await GPU", "up CPU", "running CPU", "await CPU");
    }

    @Test
    void repairsABrokenGpuModelOnceBeforeFallingBack() {
        FakeEngines healed = new FakeEngines().then(Device.GPU, Outcome.MODEL_BROKEN, Outcome.READY);
        assertThat(WhisperRoute.settle(Device.GPU, healed)).isEqualTo(Device.GPU);
        assertThat(healed.calls).containsExactly("await GPU", "drop GPU", "up GPU", "await GPU");

        FakeEngines stillBroken = new FakeEngines().then(Device.GPU, Outcome.MODEL_BROKEN, Outcome.FAILED);
        assertThat(WhisperRoute.settle(Device.GPU, stillBroken)).isEqualTo(Device.CPU);
        assertThat(stillBroken.calls).containsExactly("await GPU", "drop GPU", "up GPU", "await GPU", "up CPU", "running CPU", "await CPU");
    }

    @Test
    void repairsABrokenCpuModelAsBefore() {
        FakeEngines e = new FakeEngines().then(Device.CPU, Outcome.MODEL_BROKEN, Outcome.READY);
        assertThat(WhisperRoute.settle(Device.CPU, e)).isEqualTo(Device.CPU);
        assertThat(e.calls).containsExactly("await CPU", "drop CPU", "up CPU", "await CPU");
    }

    @Test
    void theCpuHasNothingToFallBackTo() {
        FakeEngines e = new FakeEngines().then(Device.CPU, Outcome.FAILED);
        assertThat(WhisperRoute.settle(Device.CPU, e)).isEqualTo(Device.CPU);
        assertThat(e.calls).containsExactly("await CPU");
    }

    @Test
    void doesNotWaitForTheCpuWhenItCannotBeStarted() {
        FakeEngines e = new FakeEngines().then(Device.GPU, Outcome.FAILED);
        e.upWorks = false;
        assertThat(WhisperRoute.settle(Device.GPU, e)).isEqualTo(Device.CPU);
        assertThat(e.calls).containsExactly("await GPU", "up CPU");
    }

    // ── The GPU image is downloaded in the background, never inside the blocking start ──

    private static final class FakeImages implements WhisperRoute.Images {
        final boolean present;
        final List<String> pulls = new ArrayList<>();

        FakeImages(boolean present) {
            this.present = present;
        }

        @Override
        public boolean present(String image) {
            return present;
        }

        @Override
        public void pullInBackground(String image) {
            pulls.add(image);
        }
    }

    @Test
    void usesTheGpuWhenItsImageIsAlreadyDownloaded() {
        FakeImages images = new FakeImages(true);
        assertThat(WhisperRoute.withImage(Device.GPU, "gpu:image", images)).isEqualTo(Device.GPU);
        assertThat(images.pulls).isEmpty();
    }

    @Test
    void startsOnTheCpuAndDownloadsTheGpuImageInTheBackgroundWhenItIsMissing() {
        FakeImages images = new FakeImages(false);
        assertThat(WhisperRoute.withImage(Device.GPU, "gpu:image", images)).isEqualTo(Device.CPU);
        assertThat(images.pulls).containsExactly("gpu:image");
    }

    @Test
    void theCpuNeedsNoGpuImage() {
        FakeImages images = new FakeImages(false);
        assertThat(WhisperRoute.withImage(Device.CPU, "gpu:image", images)).isEqualTo(Device.CPU);
        assertThat(images.pulls).isEmpty();
        assertThat(WhisperRoute.withImage(Device.GPU, null, images)).isEqualTo(Device.CPU);
        assertThat(images.pulls).isEmpty();
    }

    @Test
    void readsTheGpuImageFromTheShippedComposeFile() {
        assertThat(WhisperRoute.imageOf(Path.of("voice", "compose.gpu.yaml")))
                .startsWith("lscr.io/linuxserver/faster-whisper:gpu-v3.8.1-ls71@sha256:");
    }

    @Test
    void readsTheImageLineOfAComposeFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("compose.gpu.yaml");
        Files.writeString(f, "# image: not-this\nservices:\n  whisper:\n    image: \"repo/name:tag\"  # pinned\n");
        assertThat(WhisperRoute.imageOf(f)).isEqualTo("repo/name:tag");
        assertThat(WhisperRoute.imageOf(dir.resolve("missing.yaml"))).isNull();
    }

    @Test
    void dockerPullProgressCountsLayers() {
        WhisperRoute.PullProgress p = new WhisperRoute.PullProgress();
        assertThat(p.read("gpu-v3.8.1-ls71: Pulling from linuxserver/faster-whisper")).isNull();
        assertThat(p.read("a1: Already exists")).isNull();
        assertThat(p.read("b2: Pulling fs layer")).isNull();
        assertThat(p.read("c3: Pulling fs layer")).isNull();
        assertThat(p.read("b2: Download complete")).isEqualTo("2 of 3 layers downloaded");  // a1 was already here
        assertThat(p.read("b2: Pull complete")).isNull();
        assertThat(p.read("c3: Download complete")).isEqualTo("3 of 3 layers downloaded");
        assertThat(p.read("c3: Download complete")).isNull();
    }

    // ── The voice/.env switch tolerates inline comments, like Compose's own parser ──

    @Test
    void ignoresAnInlineCommentInVoiceDotEnv(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "HARIS_WHISPER_GPU=false   # cpu only\nA=x#y\nB=\"quoted # kept\"  # comment\n");
        assertThat(WhisperRoute.dotEnv(env, WhisperRoute.SWITCH)).isEqualTo("false");
        assertThat(WhisperRoute.dotEnv(env, "A")).isEqualTo("x#y");
        assertThat(WhisperRoute.dotEnv(env, "B")).isEqualTo("quoted # kept");
    }

    // ── A slow `docker inspect` is not an exit ──

    @Test
    void anEmptyInspectAnswerIsUnknownNotAnExit() {
        assertThat(WhisperRoute.containerState("running\n")).isEqualTo(WhisperRoute.State.UP);
        assertThat(WhisperRoute.containerState("created")).isEqualTo(WhisperRoute.State.UP);
        assertThat(WhisperRoute.containerState("restarting")).isEqualTo(WhisperRoute.State.UP);
        assertThat(WhisperRoute.containerState("exited")).isEqualTo(WhisperRoute.State.GONE);
        assertThat(WhisperRoute.containerState("Error: No such object: haris-voice-whisper-1")).isEqualTo(WhisperRoute.State.GONE);
        assertThat(WhisperRoute.containerState("")).isEqualTo(WhisperRoute.State.UNKNOWN);
        assertThat(WhisperRoute.containerState(null)).isEqualTo(WhisperRoute.State.UNKNOWN);
    }

    // ── A GPU that failed is not tried again on every launch ──

    @Test
    void aRecentGpuFailureWithTheSameImageKeepsTheCpu() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 15);
        String marker = WhisperRoute.failureMarker("gpu:image", today.minusDays(1));
        assertThat(WhisperRoute.gpuRecentlyFailed(marker, "gpu:image", today)).isTrue();
        assertThat(WhisperRoute.gpuRecentlyFailed(marker, "gpu:other", today)).isFalse();
        assertThat(WhisperRoute.gpuRecentlyFailed(marker, "gpu:image", today.plusDays(WhisperRoute.RETRY_GPU_DAYS))).isFalse();
        assertThat(WhisperRoute.gpuRecentlyFailed(null, "gpu:image", today)).isFalse();
        assertThat(WhisperRoute.gpuRecentlyFailed("garbage", "gpu:image", today)).isFalse();
    }

    // ── WHISPER_OFFLINE reaches both containers ──

    @Test
    void offlineModeIsSetForBothWhisperImages() throws Exception {
        String cpu = Files.readString(Path.of("voice", "compose.yaml"));
        String gpu = Files.readString(Path.of("voice", "compose.gpu.yaml"));
        // wyoming-faster-whisper reads WYO_WHISPER_<OPTION> itself (env_args.py), so it holds even with the
        // GPU image's s6 scripts bypassed; HF_HUB_OFFLINE makes huggingface_hub refuse downloads as well.
        assertThat(cpu).contains("WYO_WHISPER_LOCAL_FILES_ONLY: \"${WHISPER_OFFLINE:-false}\"");
        assertThat(cpu).contains("HF_HUB_OFFLINE: \"${WHISPER_OFFLINE:-false}\"");
        // The override merges its environment into compose.yaml's; it must not reset or replace it.
        assertThat(gpu).doesNotContain("environment: !override").doesNotContain("environment: !reset");
        assertThat(gpu).contains("\"-m\", \"wyoming_faster_whisper\"");
    }
}

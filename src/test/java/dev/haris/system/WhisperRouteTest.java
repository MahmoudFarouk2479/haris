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
        assertThat(e.calls).containsExactly("await GPU", "up CPU", "await CPU");
    }

    @Test
    void repairsABrokenGpuModelOnceBeforeFallingBack() {
        FakeEngines healed = new FakeEngines().then(Device.GPU, Outcome.MODEL_BROKEN, Outcome.READY);
        assertThat(WhisperRoute.settle(Device.GPU, healed)).isEqualTo(Device.GPU);
        assertThat(healed.calls).containsExactly("await GPU", "drop GPU", "up GPU", "await GPU");

        FakeEngines stillBroken = new FakeEngines().then(Device.GPU, Outcome.MODEL_BROKEN, Outcome.FAILED);
        assertThat(WhisperRoute.settle(Device.GPU, stillBroken)).isEqualTo(Device.CPU);
        assertThat(stillBroken.calls).containsExactly("await GPU", "drop GPU", "up GPU", "await GPU", "up CPU", "await CPU");
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
}

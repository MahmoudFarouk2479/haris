package dev.haris.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class WakeWordDetectorTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void wakesOnAGreetingFollowedByTheName() {
        assertThat(heard("{\"partial\": \"hey harris open notepad\"}")).isTrue();
        assertThat(heard("{\"text\": \"okay haris\"}")).isTrue();
        assertThat(heard("{\"partial\": \"ya harris\"}")).isTrue();
    }

    @Test
    void ignoresTheNameWithoutAGreeting() {
        assertThat(heard("{\"partial\": \"kamala harris spoke to reporters\"}")).isFalse();
        assertThat(heard("{\"text\": \"harris is lovely hey really nice\"}")).isFalse();
        assertThat(heard("{\"partial\": \"hey harry are you coming to paris\"}")).isFalse();
        assertThat(heard("{\"partial\": \"hey harrison\"}")).isFalse();
        assertThat(heard("{\"partial\": \"\"}")).isFalse();
    }

    private boolean heard(String result) {
        return WakeWordDetector.mentionsWakeWord(json.readTree(result));
    }

    @Test
    void measuresLoudness() {
        assertThat(WakeWordDetector.rms(new byte[3200])).isZero();
        byte[] loud = new byte[3200];
        for (int i = 0; i < loud.length; i += 2) {
            loud[i + 1] = (byte) ((i / 2) % 2 == 0 ? 0x40 : 0xC0);   // ±16384, half scale
        }
        assertThat(WakeWordDetector.rms(loud)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * Accuracy check over recorded clips: files named pos* must wake, neg* must not. Runs only when
     * HARIS_WAKE_CLIPS points at a folder of 16 kHz 16-bit mono .pcm files and the model is installed.
     */
    @Test
    void wakesOnTheNameAndNotOnSimilarWords() throws IOException {
        String dir = System.getenv("HARIS_WAKE_CLIPS");
        assumeTrue(dir != null && WakeWordDetector.modelDir().isPresent(), "no clips or model");
        WakeWordDetector detector = new WakeWordDetector(json);
        List<Path> clips;
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            clips = files.filter(p -> p.toString().endsWith(".pcm")).sorted().toList();
        }
        for (Path clip : clips) {
            byte[] pcm = Files.readAllBytes(clip);
            boolean woke = false;
            try (WakeWordDetector.Stream stream = detector.open().orElseThrow()) {
                for (int off = 0; off < pcm.length && !woke; off += 2560) {
                    woke = stream.accept(Arrays.copyOfRange(pcm, off, Math.min(pcm.length, off + 2560)));
                }
                // trailing silence, as the microphone keeps streaming after the speaker stops
                for (int i = 0; i < 25 && !woke; i++) {
                    woke = stream.accept(new byte[2560]);
                }
            }
            boolean expected = clip.getFileName().toString().startsWith("pos");
            assertThat(woke).as(clip.getFileName().toString()).isEqualTo(expected);
        }
        detector.close();
    }
}

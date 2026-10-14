package dev.haris.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import dev.haris.system.EnginesService;
import org.junit.jupiter.api.Test;

/** Text to speak can be steered by injected content, so cleaning it must stay cheap (F8). */
class VoiceControllerTest {

    @Test
    void cleaningAnAdversarialRunOfLinkBracketsFinishesQuickly() {
        String hostile = "[](".repeat(2000);
        String cleaned = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> VoiceController.clean(hostile));
        assertThat(cleaned).isNotNull();
    }

    @Test
    void markdownLinksStillBecomeTheirLabel() {
        assertThat(VoiceController.clean("See [the docs](https://example.com/a b) now")).isEqualTo("See the docs now");
        assertThat(VoiceController.clean("[label](http://x)")).isEqualTo("label");
    }

    @Test
    void oversizedTextIsRefusedBeforeAnyCleaningOrSynthesis() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        EnginesService startup = mock(EnginesService.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        when(engines.synthesize(any(), any())).thenReturn(new byte[0]);
        VoiceController controller = new VoiceController(engines, mock(ColabSpeech.class),
                mock(WakeWordDetector.class), mock(GeminiSpeech.class), settings, startup);

        // A huge code block shrinks to "(code omitted) hello" after cleaning, so only a raw limit stops it.
        String raw = "```" + "x".repeat(7000) + "``` hello";
        assertThat(controller.speak(new VoiceController.SpeakRequest(raw)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.speak(new VoiceController.SpeakRequest(null)).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(engines, startup);
    }

    private static byte[] tone(double seconds, double amplitude) {
        int n = (int) (16_000 * seconds);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 220 * i / 16_000.0) * amplitude * 32767);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    private VoiceController transcribing(VoiceEngines engines) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        ColabSpeech colab = mock(ColabSpeech.class);
        when(colab.available()).thenReturn(java.util.Optional.empty());
        return new VoiceController(engines, colab, mock(WakeWordDetector.class), mock(GeminiSpeech.class),
                settings, mock(EnginesService.class));
    }

    @Test
    void recordingsWithoutSpeechAreNotTranscribed() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        VoiceController controller = transcribing(engines);

        // one second of silence, and one of faint room noise
        assertThat(controller.transcribe(new byte[32_000]).getBody()).containsEntry("text", "");
        assertThat(controller.transcribe(tone(1.0, 0.004)).getBody()).containsEntry("text", "");
        // a click: loud, but far too short to be a word
        byte[] click = new byte[32_000];
        System.arraycopy(tone(0.16, 0.5), 0, click, 0, tone(0.16, 0.5).length);
        assertThat(controller.transcribe(click).getBody()).containsEntry("text", "");
        verifyNoInteractions(engines);
    }

    @Test
    void speechIsTranscribed() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        when(engines.transcribe(any(), any())).thenReturn("hello");
        assertThat(transcribing(engines).transcribe(tone(1.0, 0.3)).getBody()).containsEntry("text", "hello");
    }

    @Test
    void aNewRecordingWhileOneIsStillBeingTranscribedIsRefusedNotQueued() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(engines.transcribe(any(), any())).thenAnswer(call -> {
            inside.countDown();
            release.await();
            return "first";
        });
        VoiceController controller = transcribing(engines);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> controller.transcribe(tone(1.0, 0.3)));
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        try {
            var second = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> controller.transcribe(tone(1.0, 0.3)));
            assertThat(second.getStatusCode().value()).isEqualTo(429);
            assertThat(second.getBody().get("error")).contains("previous recording");
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).getBody()).containsEntry("text", "first");
    }

    @Test
    void theStatusSaysWhenGeminiIsPausedByItsLimit() {
        VoiceEngines engines = mock(VoiceEngines.class);
        when(engines.status()).thenReturn(java.util.Map.of("stt", true, "tts", true));
        GeminiSpeech gemini = mock(GeminiSpeech.class);
        when(gemini.selected()).thenReturn(true);
        when(gemini.pausedUntil()).thenReturn(System.currentTimeMillis() + 3_600_000);
        ColabSpeech colab = mock(ColabSpeech.class);
        when(colab.target()).thenReturn(java.util.Optional.empty());
        VoiceController controller = new VoiceController(engines, colab, mock(WakeWordDetector.class), gemini,
                mock(SettingsService.class), mock(EnginesService.class));

        var status = controller.status();

        assertThat(status.get("ttsRoute")).isEqualTo("gemini-paused");
        assertThat((Long) status.get("ttsResumeAt")).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void geminiSpeaksWhenChosenAndPiperIsNotUsed() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        GeminiSpeech gemini = mock(GeminiSpeech.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        when(gemini.available()).thenReturn(java.util.Optional.of("key"));
        when(gemini.speak("key", "أهلاً يا محمود")).thenReturn(new byte[] {1, 2, 3});
        VoiceController controller = new VoiceController(engines, mock(ColabSpeech.class),
                mock(WakeWordDetector.class), gemini, settings, mock(EnginesService.class));

        var reply = controller.speak(new VoiceController.SpeakRequest("أهلاً يا محمود"));

        assertThat(reply.getStatusCode().value()).isEqualTo(200);
        assertThat(reply.getBody()).containsExactly(1, 2, 3);
        verifyNoInteractions(engines);
    }

    @Test
    void piperSpeaksWhenGeminiFails() throws Exception {
        VoiceEngines engines = mock(VoiceEngines.class);
        GeminiSpeech gemini = mock(GeminiSpeech.class);
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults());
        when(gemini.available()).thenReturn(java.util.Optional.of("key"));
        when(gemini.speak(any(), any())).thenThrow(new java.io.IOException("Gemini speech answered HTTP 503"));
        when(engines.synthesize(any(), any())).thenReturn(new byte[] {9});
        VoiceController controller = new VoiceController(engines, mock(ColabSpeech.class),
                mock(WakeWordDetector.class), gemini, settings, mock(EnginesService.class));

        var reply = controller.speak(new VoiceController.SpeakRequest("Hello there."));

        assertThat(reply.getStatusCode().value()).isEqualTo(200);
        assertThat(reply.getBody()).containsExactly(9);
    }
}

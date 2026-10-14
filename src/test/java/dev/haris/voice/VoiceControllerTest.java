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

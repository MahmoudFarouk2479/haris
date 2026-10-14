package dev.haris.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsValidationTest {

    private static Settings with(String ollama, String cloud) {
        return new Settings("Haris", "", ProviderKind.OLLAMA, "claude-opus-5-5", "medium", false,
                ollama, "", "http://127.0.0.1:1234", "", cloud, "", List.of(), "",
                true, false, "auto", "en_GB-alan-medium", "ar_JO-kareem-medium", true, false, "", "", "auto", true, true, "piper", "Charon");
    }

    @Test
    void localEnginesMustBeOnThisMachine() {
        SettingsService.validateEndpoints(with("http://127.0.0.1:11434", ""));
        SettingsService.validateEndpoints(with("http://localhost:11434", ""));
        for (String bad : List.of("http://192.168.1.20:11434", "http://evil.com:11434", "http://127.0.0.1.nip.io:11434",
                "file:///c:/x", "http://user:pw@127.0.0.1:11434", "http://127.0.0.1:11434?x=1")) {
            assertThatThrownBy(() -> SettingsService.validateEndpoints(with(bad, ""))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void colabMustBeARemoteHttpsTunnel() {
        SettingsService.validateEndpoints(withColab("https://abc-def.trycloudflare.com"));
        for (String bad : List.of("http://abc-def.trycloudflare.com", "https://127.0.0.1:8000", "https://localhost",
                "https://user:pw@abc.trycloudflare.com", "https://abc.trycloudflare.com?x=1", "ftp://abc.example.com")) {
            assertThatThrownBy(() -> SettingsService.validateEndpoints(withColab(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void pastedColabExampleUrlsAreReducedToTheirOrigin() {
        assertThat(SettingsService.colabOrigin(
                "https://11434-m-s-kkb-usw3b1-1tdlmc5ixpksg-b.us-west3-1.prod.colab.dev/api/generate"))
                .isEqualTo("https://11434-m-s-kkb-usw3b1-1tdlmc5ixpksg-b.us-west3-1.prod.colab.dev");
        assertThat(SettingsService.colabOrigin("https://Abc.trycloudflare.com/v1/")).isEqualTo("https://abc.trycloudflare.com");
        assertThat(SettingsService.colabOrigin("")).isEmpty();
    }

    private static Settings withColab(String url) {
        return new Settings("Haris", "", ProviderKind.COLAB, "claude-opus-5-5", "medium", false,
                "http://127.0.0.1:11434", "", "http://127.0.0.1:1234", "", "", "", List.of(), "",
                true, false, "auto", "en_GB-alan-medium", "ar_JO-kareem-medium", true, false, url, "qwen2.5:7b", "auto", true, true, "piper", "Charon");
    }

    @Test
    void cloudEndpointsMustUseHttps() {
        SettingsService.validateEndpoints(with("http://127.0.0.1:11434", "https://api.openai.com"));
        assertThatThrownBy(() -> SettingsService.validateEndpoints(with("http://127.0.0.1:11434", "http://api.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordinaryFoldersCanBeGranted() throws Exception {
        // Not @TempDir: that lives under AppData, which the guard (correctly) refuses.
        Path ok = Files.createDirectories(Path.of("target", "granted-folder-test")).toAbsolutePath();
        assertThat(SettingsService.validateFolders(List.of(ok.toString()))).containsExactly(ok.toRealPath().toString());
    }

    @Test
    void foldersUnderAppDataAreRefused(@TempDir Path tmp) {
        assertThatThrownBy(() -> SettingsService.validateFolders(List.of(tmp.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("off-limits");
    }

    @Test
    void sensitiveFoldersCannotBeGranted(@TempDir Path tmp) throws Exception {

        String home = System.getProperty("user.home");
        List<String> forbidden = List.of(home, Path.of(home).getRoot().toString(),
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                System.getenv().getOrDefault("APPDATA", home + "\\AppData\\Roaming"));
        for (String f : forbidden) {
            if (Files.isDirectory(Path.of(f))) {
                assertThatThrownBy(() -> SettingsService.validateFolders(List.of(f))).as(f)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> SettingsService.validateFolders(List.of(tmp.resolve("missing").toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spokenRepliesUseAKnownEngineAndGeminiVoice() {
        Settings ok = Settings.defaults();
        assertThat(ok.ttsEngine()).isEqualTo("piper");
        SettingsService.validateVoices(ok);
        SettingsService.validateVoices(withSpeech("gemini", "Kore"));
        assertThatThrownBy(() -> SettingsService.validateVoices(withSpeech("elevenlabs", "Kore")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingsService.validateVoices(withSpeech("gemini", "Darth")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Settings withSpeech(String engine, String voice) {
        Settings d = Settings.defaults();
        return new Settings(d.assistantName(), d.userName(), d.provider(), d.anthropicModel(), d.anthropicEffort(),
                d.webSearch(), d.ollamaBaseUrl(), d.ollamaModel(), d.lmStudioBaseUrl(), d.lmStudioModel(),
                d.openAiBaseUrl(), d.openAiModel(), d.allowedFolders(), d.homeCity(), d.voiceReplies(), d.voiceWake(),
                d.sttLanguage(), d.voiceEnglish(), d.voiceArabic(), d.morningBriefing(), d.startWithWindows(),
                d.colabBaseUrl(), d.colabModel(), d.replyLanguage(), d.voiceFollowUp(), d.colabStt(), engine, voice);
    }
}

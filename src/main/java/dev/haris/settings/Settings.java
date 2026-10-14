package dev.haris.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Everything the user can configure that is not a secret. Saved as plain JSON in
 * ~/.haris/settings.json; API keys are deliberately absent (see SecretStore).
 */
public record Settings(
        @NotBlank @Size(max = 40) String assistantName,
        @Size(max = 40) String userName,
        @NotNull ProviderKind provider,

        @NotBlank @Size(max = 80) String anthropicModel,
        @NotBlank @Pattern(regexp = "low|medium|high|xhigh|max") String anthropicEffort,
        Boolean webSearch,

        @NotBlank @Size(max = 200) String ollamaBaseUrl,
        @Size(max = 120) String ollamaModel,

        @NotBlank @Size(max = 200) String lmStudioBaseUrl,
        @Size(max = 120) String lmStudioModel,

        @Size(max = 200) String openAiBaseUrl,
        @Size(max = 120) String openAiModel,

        /** The only folders file tools may touch. */
        @Size(max = 10) List<@Size(max = 260) String> allowedFolders,

        @Size(max = 60) String homeCity,

        /** Speak replies aloud. */
        Boolean voiceReplies,
        /** Keep the microphone open for "Hey Haris" (audio goes only to the local wake-word engine). */
        Boolean voiceWake,
        @Pattern(regexp = "auto|ar|en") String sttLanguage,
        @Pattern(regexp = VOICE_NAME) String voiceEnglish,
        @Pattern(regexp = VOICE_NAME) String voiceArabic,

        /** Greet with a short briefing on the first launch of each day. */
        Boolean morningBriefing,
        /** Launch Haris when the user signs in to Windows (installed app only). */
        Boolean startWithWindows,

        /** The https:// tunnel address printed by the Colab notebook. */
        @Size(max = 200) String colabBaseUrl,
        @Size(max = 120) String colabModel,

        /** auto: answer in the user's language; ar: always Egyptian Arabic; en: always English. */
        @Pattern(regexp = "auto|ar|en") String replyLanguage,
        /** After a spoken reply, listen a few seconds for a follow-up without the wake word. */
        Boolean voiceFollowUp,
        /** With the Colab engine, also transcribe speech on Colab's GPU (falls back to the local engine). */
        Boolean colabStt,
        /** piper: spoken replies made on this PC; gemini: Google's natural voices (reply text goes to Google). */
        @Pattern(regexp = "piper|gemini") String ttsEngine,
        /** One of Gemini's prebuilt voice names. */
        @Size(max = 20) String geminiVoice,
        /** The HUD's own language: en (default) or ar (right-to-left). Replies follow replyLanguage. */
        @Pattern(regexp = "en|ar") String uiLanguage) {

    /** Piper voice ids look like en_GB-alan-medium. */
    public static final String VOICE_NAME = "[a-z]{2,3}_[A-Z]{2}-[A-Za-z0-9_]{1,40}-(x_low|low|medium|high)";

    public static Settings defaults() {
        return new Settings(
                "Haris", "",
                ProviderKind.OLLAMA,
                "claude-opus-5-5", "medium", false,
                "http://127.0.0.1:11434", "",
                "http://127.0.0.1:1234", "",
                "https://api.openai.com", "",
                defaultFolders(),
                "",
                true, false, "auto", "en_GB-alan-medium", "ar_JO-kareem-medium",
                true, false,
                "", "",
                "auto", true, true,
                "piper", "Charon", "en");
    }

    /** Fills fields that older settings files did not have. */
    public Settings withDefaults() {
        Settings d = defaults();
        return new Settings(
                assistantName != null ? assistantName : d.assistantName,
                userName != null ? userName : "",
                provider != null ? provider : d.provider,
                anthropicModel != null ? anthropicModel : d.anthropicModel,
                anthropicEffort != null ? anthropicEffort : d.anthropicEffort,
                webSearch != null ? webSearch : Boolean.FALSE,
                ollamaBaseUrl != null ? ollamaBaseUrl : d.ollamaBaseUrl,
                ollamaModel != null ? ollamaModel : "",
                lmStudioBaseUrl != null ? lmStudioBaseUrl : d.lmStudioBaseUrl,
                lmStudioModel != null ? lmStudioModel : "",
                openAiBaseUrl != null ? openAiBaseUrl : d.openAiBaseUrl,
                openAiModel != null ? openAiModel : "",
                allowedFolders != null ? List.copyOf(allowedFolders) : d.allowedFolders,
                homeCity != null ? homeCity : "",
                voiceReplies != null ? voiceReplies : d.voiceReplies,
                voiceWake != null ? voiceWake : d.voiceWake,
                sttLanguage != null ? sttLanguage : d.sttLanguage,
                voiceEnglish != null ? voiceEnglish : d.voiceEnglish,
                voiceArabic != null ? voiceArabic : d.voiceArabic,
                morningBriefing != null ? morningBriefing : d.morningBriefing,
                startWithWindows != null ? startWithWindows : d.startWithWindows,
                colabBaseUrl != null ? colabBaseUrl : "",
                colabModel != null ? colabModel : "",
                replyLanguage != null ? replyLanguage : d.replyLanguage,
                voiceFollowUp != null ? voiceFollowUp : d.voiceFollowUp,
                colabStt != null ? colabStt : d.colabStt,
                ttsEngine != null ? ttsEngine : d.ttsEngine,
                geminiVoice != null ? geminiVoice : d.geminiVoice,
                uiLanguage != null ? uiLanguage : d.uiLanguage);
    }

    /** A copy with the Colab address replaced (used after normalisation). */
    public Settings withColabBaseUrl(String url) {
        return new Settings(assistantName, userName, provider, anthropicModel, anthropicEffort, webSearch,
                ollamaBaseUrl, ollamaModel, lmStudioBaseUrl, lmStudioModel, openAiBaseUrl, openAiModel,
                allowedFolders, homeCity, voiceReplies, voiceWake, sttLanguage, voiceEnglish, voiceArabic,
                morningBriefing, startWithWindows, url, colabModel, replyLanguage, voiceFollowUp, colabStt, ttsEngine, geminiVoice, uiLanguage);
    }

    /** A copy with the granted folders replaced (used after validation). */
    public Settings withFolders(List<String> folders) {
        return new Settings(assistantName, userName, provider, anthropicModel, anthropicEffort, webSearch,
                ollamaBaseUrl, ollamaModel, lmStudioBaseUrl, lmStudioModel, openAiBaseUrl, openAiModel,
                folders, homeCity, voiceReplies, voiceWake, sttLanguage, voiceEnglish, voiceArabic,
                morningBriefing, startWithWindows, colabBaseUrl, colabModel, replyLanguage, voiceFollowUp, colabStt, ttsEngine, geminiVoice, uiLanguage);
    }

    /** A copy with the HUD's language replaced (used to tell a language switch from a rule change). */
    public Settings withUiLanguage(String language) {
        return new Settings(assistantName, userName, provider, anthropicModel, anthropicEffort, webSearch,
                ollamaBaseUrl, ollamaModel, lmStudioBaseUrl, lmStudioModel, openAiBaseUrl, openAiModel,
                allowedFolders, homeCity, voiceReplies, voiceWake, sttLanguage, voiceEnglish, voiceArabic,
                morningBriefing, startWithWindows, colabBaseUrl, colabModel, replyLanguage, voiceFollowUp, colabStt, ttsEngine, geminiVoice, language);
    }

    private static List<String> defaultFolders() {
        Path home = Path.of(System.getProperty("user.home"));
        return Stream.of("Desktop", "Documents", "Downloads")
                .map(home::resolve)
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
    }
}

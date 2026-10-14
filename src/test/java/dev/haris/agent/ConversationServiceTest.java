package dev.haris.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import dev.haris.settings.SettingsService.SettingsChanged;
import dev.haris.tools.PathGuard;
import org.junit.jupiter.api.Test;

/** A new rule set means a new conversation; the HUD's own language is not a rule. */
class ConversationServiceTest {

    private final Settings before = Settings.defaults();

    private ConversationService service() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(before);
        PathGuard paths = mock(PathGuard.class);
        when(paths.roots()).thenReturn(List.of());
        return new ConversationService(settings, paths, new ConfirmationBroker());
    }

    @Test
    void switchingOnlyTheInterfaceLanguageKeepsTheConversation() {
        ConversationService conversations = service();
        Conversation kept = conversations.current();

        conversations.onSettingsChanged(new SettingsChanged(before, before.withUiLanguage("ar")));
        assertThat(conversations.current()).isSameAs(kept);

        conversations.onSettingsChanged(new SettingsChanged(before, before));   // Save with no changes
        assertThat(conversations.current()).isSameAs(kept);
    }

    @Test
    void anyOtherChangeStartsAFreshConversation() {
        ConversationService conversations = service();
        Conversation old = conversations.current();
        Settings renamed = new Settings(before.assistantName(), "Mahmoud", before.provider(), before.anthropicModel(),
                before.anthropicEffort(), before.webSearch(), before.ollamaBaseUrl(), before.ollamaModel(),
                before.lmStudioBaseUrl(), before.lmStudioModel(), before.openAiBaseUrl(), before.openAiModel(),
                before.allowedFolders(), before.homeCity(), before.voiceReplies(), before.voiceWake(),
                before.sttLanguage(), before.voiceEnglish(), before.voiceArabic(), before.morningBriefing(),
                before.startWithWindows(), before.colabBaseUrl(), before.colabModel(), before.replyLanguage(),
                before.voiceFollowUp(), before.colabStt(), before.ttsEngine(), before.geminiVoice(), "ar");

        conversations.onSettingsChanged(new SettingsChanged(before, renamed));
        assertThat(conversations.current()).isNotSameAs(old);
    }

    @Test
    void sameRulesIgnoresOnlyTheInterfaceLanguage() {
        assertThat(new SettingsChanged(before, before.withUiLanguage("ar")).sameRules()).isTrue();
        assertThat(new SettingsChanged(before, before.withFolders(List.of("C:\\Projects"))).sameRules()).isFalse();
        assertThat(new SettingsChanged(before, before.withColabBaseUrl("https://x.trycloudflare.com")).sameRules())
                .isFalse();
        assertThat(new SettingsChanged(null, before).sameRules()).isFalse();
    }
}

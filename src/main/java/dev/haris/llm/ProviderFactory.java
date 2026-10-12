package dev.haris.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.time.Duration;

import dev.haris.security.SecretStore;
import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Builds the provider the user selected, reading keys from the OS vault at call time. */
@Component
public class ProviderFactory {

    /**
     * Remote servers on a GPU answer within seconds; two minutes of silence means the session or
     * tunnel is gone (local engines keep a long wait: a big model on a CPU can be that slow).
     */
    private static final Duration REMOTE_PATIENCE = Duration.ofMinutes(2);

    private final SettingsService settings;
    private final SecretStore secrets;
    private final JsonMapper json;

    private String cachedKey;
    private AnthropicClient cachedClient;

    public ProviderFactory(SettingsService settings, SecretStore secrets, JsonMapper json) {
        this.settings = settings;
        this.secrets = secrets;
        this.json = json;
    }

    public ChatProvider current() {
        Settings s = settings.get();
        return switch (s.provider()) {
            case ANTHROPIC -> new AnthropicProvider(anthropicClient(), s.anthropicModel(), s.anthropicEffort(),
                    Boolean.TRUE.equals(s.webSearch()), json);
            case OLLAMA -> new OpenAiCompatibleProvider("Ollama", s.ollamaBaseUrl(), s.ollamaModel(), null, json);
            case LM_STUDIO -> new OpenAiCompatibleProvider("LM Studio", s.lmStudioBaseUrl(), s.lmStudioModel(), null, json);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider("Cloud", s.openAiBaseUrl(), s.openAiModel(),
                    secrets.get(SecretStore.OPENAI_COMPATIBLE)
                            .orElseThrow(() -> new ProviderException("No API key saved for the cloud provider. Add it in Settings.")),
                    json, REMOTE_PATIENCE);
            case COLAB -> {
                if (s.colabBaseUrl() == null || s.colabBaseUrl().isBlank()) {
                    throw new ProviderException("No Colab address yet. Run the notebook and paste its https:// link in Settings.");
                }
                // The token is optional: without the notebook's proxy (e.g. Colab's own port address
                // straight to Ollama) there is none. Settings warns the user about that setup.
                yield new OpenAiCompatibleProvider("Colab", s.colabBaseUrl(), s.colabModel(),
                        secrets.get(SecretStore.COLAB).orElse(null), json, REMOTE_PATIENCE);
            }
        };
    }

    /**
     * True when the selected engine has what it needs to answer (a model, and a key where one is
     * required). On a fresh install nothing is set up yet, so Haris asks for an engine instead of
     * failing on its first request.
     */
    public boolean ready() {
        Settings s = settings.get();
        return switch (s.provider()) {
            case ANTHROPIC -> secrets.has(SecretStore.ANTHROPIC);
            case OLLAMA -> notBlank(s.ollamaModel());
            case LM_STUDIO -> notBlank(s.lmStudioModel());
            case OPENAI_COMPATIBLE -> notBlank(s.openAiModel()) && secrets.has(SecretStore.OPENAI_COMPATIBLE);
            case COLAB -> notBlank(s.colabBaseUrl()) && notBlank(s.colabModel());
        };
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** True when the selected engine runs on this machine, so nothing it is shown leaves the computer. */
    public boolean isLocal() {
        return switch (settings.get().provider()) {
            case OLLAMA, LM_STUDIO -> true;
            case ANTHROPIC, OPENAI_COMPATIBLE, COLAB -> false;   // Colab runs on Google's servers
        };
    }

    private synchronized AnthropicClient anthropicClient() {
        String key = secrets.get(SecretStore.ANTHROPIC)
                .orElseThrow(() -> new ProviderException("No Claude API key saved. Add it in Settings."));
        if (!key.equals(cachedKey)) {
            if (cachedClient != null) {
                cachedClient.close();
            }
            cachedClient = AnthropicOkHttpClient.builder().apiKey(key).build();
            cachedKey = key;
        }
        return cachedClient;
    }
}

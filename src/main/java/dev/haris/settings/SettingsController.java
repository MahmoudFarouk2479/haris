package dev.haris.settings;

import java.util.Map;

import jakarta.validation.Valid;

import dev.haris.llm.ProviderFactory;
import dev.haris.security.SecretStore;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final SettingsService settings;
    private final SecretStore secrets;
    private final ProviderFactory providers;

    public SettingsController(SettingsService settings, SecretStore secrets, ProviderFactory providers) {
        this.providers = providers;
        this.settings = settings;
        this.secrets = secrets;
    }

    public record SettingsView(Settings settings, Map<String, Boolean> secrets, boolean engineReady) {}

    public record SecretValue(String value) {}

    @GetMapping("/settings")
    public SettingsView get() {
        return view();
    }

    @PutMapping("/settings")
    public SettingsView put(@Valid @RequestBody Settings next) {
        settings.update(next);
        return view();
    }

    /** Write-only: a key goes in, and only "is one configured?" ever comes back out. */
    @PutMapping("/secrets/{name}")
    public SettingsView putSecret(@PathVariable String name, @RequestBody SecretValue body) {
        requireUserKey(name);
        secrets.put(name, body.value());
        return view();
    }

    @DeleteMapping("/secrets/{name}")
    public SettingsView deleteSecret(@PathVariable String name) {
        requireUserKey(name);
        secrets.delete(name);
        return view();
    }

    /** Internal secrets (the memory key, SearXNG's secret) can never be replaced or wiped from the HUD. */
    private static void requireUserKey(String name) {
        if (!SecretStore.USER_KEYS.contains(name)) {
            throw new IllegalArgumentException("Unknown key: " + name);
        }
    }

    private SettingsView view() {
        return new SettingsView(settings.get(), Map.of(
                SecretStore.ANTHROPIC, secrets.has(SecretStore.ANTHROPIC),
                SecretStore.OPENAI_COMPATIBLE, secrets.has(SecretStore.OPENAI_COMPATIBLE),
                SecretStore.COLAB, secrets.has(SecretStore.COLAB),
                SecretStore.GEMINI, secrets.has(SecretStore.GEMINI)), providers.ready());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** A field the annotations refuse (e.g. uiLanguage "fr"): say which one, never echo the value. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidField(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldError();
        String message = field == null ? "Invalid settings." : "Invalid value for " + field.getField() + ".";
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}

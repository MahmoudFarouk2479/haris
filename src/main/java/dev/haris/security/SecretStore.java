package dev.haris.security;

import java.util.Optional;
import java.util.Set;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API keys are stored in the operating system's credential vault (Windows Credential
 * Manager, macOS Keychain, Secret Service on Linux) and never in a file in the project.
 * The UI can write a key and ask whether one exists; it can never read one back.
 */
@Component
public class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);
    private static final String SERVICE = "Haris";
    /** Where versions from before the rename (JARVIS) kept the same secrets. */
    private static final String LEGACY_SERVICE = "JARVIS";

    public static final String ANTHROPIC = "anthropic";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    /** The access token printed by the Colab notebook; its proxy rejects requests without it. */
    public static final String COLAB = "colab";
    /** Google AI Studio key, used only for Gemini's spoken replies when the user chooses them. */
    public static final String GEMINI = "gemini";
    /** AES key for the memory file. Created by the app; never accepted from or shown to the UI. */
    public static final String MEMORY_KEY = "memory-key";
    /** SearXNG's server secret. Created by the app. */
    public static final String SEARXNG_SECRET = "searxng-secret";

    public static final Set<String> NAMES = Set.of(ANTHROPIC, OPENAI_COMPATIBLE, COLAB, GEMINI, MEMORY_KEY, SEARXNG_SECRET);
    /** The only secrets the HUD may write or delete: the user's own API keys. */
    public static final Set<String> USER_KEYS = Set.of(ANTHROPIC, OPENAI_COMPATIBLE, COLAB, GEMINI);

    /** Opens the OS vault; a test stands in for it. */
    @FunctionalInterface
    interface Vault {
        Keyring open() throws BackendNotSupportedException;
    }

    Vault vault = Keyring::create;

    /** Returns the stored value, creating a random 256-bit one first if there is none. */
    public synchronized String getOrCreate(String name) {
        return get(name).orElseGet(() -> {
            byte[] bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            String value = java.util.Base64.getEncoder().encodeToString(bytes);
            put(name, value);
            return get(name).orElseThrow(() -> new IllegalStateException("The OS credential vault is unavailable."));
        });
    }

    public Optional<String> get(String name) {
        requireKnown(name);
        try (Keyring keyring = vault.open()) {
            String value = keyring.getPassword(SERVICE, name);
            return Optional.ofNullable(value).filter(v -> !v.isBlank());
        } catch (PasswordAccessException e) {
            return migrateLegacy(name);   // nothing stored under this name yet
        } catch (BackendNotSupportedException e) {
            log.error("No OS credential vault available: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Credential vault read failed for {}", name);
            return Optional.empty();
        }
    }

    /** Moves a secret stored by the old JARVIS version to the current name, once. */
    private Optional<String> migrateLegacy(String name) {
        try (Keyring keyring = vault.open()) {
            String value = keyring.getPassword(LEGACY_SERVICE, name);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            keyring.setPassword(SERVICE, name, value);
            keyring.deletePassword(LEGACY_SERVICE, name);
            log.info("Moved stored secret {} from the old JARVIS vault entry", name);
            return Optional.of(value);
        } catch (Exception e) {
            return Optional.empty();   // no legacy entry either
        }
    }

    public boolean has(String name) {
        return get(name).isPresent();
    }

    public void put(String name, String value) {
        requireKnown(name);
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("Invalid secret value");
        }
        try (Keyring keyring = vault.open()) {
            keyring.setPassword(SERVICE, name, value.strip());
        } catch (Exception e) {
            throw new IllegalStateException("Could not save to the OS credential vault", e);
        }
    }

    /**
     * Deletes the secret, and also the old JARVIS entry of the same name: otherwise the next read
     * would find that one and move it back (see {@link #migrateLegacy}), undoing the removal.
     */
    public void delete(String name) {
        requireKnown(name);
        try (Keyring keyring = vault.open()) {
            for (String service : new String[] {SERVICE, LEGACY_SERVICE}) {
                try {
                    keyring.deletePassword(service, name);
                } catch (PasswordAccessException e) {
                    // already absent
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete from the OS credential vault", e);
        }
    }

    private static void requireKnown(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("Unknown secret: " + name);
        }
    }
}

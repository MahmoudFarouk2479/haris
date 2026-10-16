package dev.haris.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Removing a key from Settings must stay removed, also when an old JARVIS entry of it exists. */
class SecretStoreTest {

    private final Map<String, String> entries = new HashMap<>();
    private SecretStore store;

    @BeforeEach
    void setUp() throws Exception {
        Keyring keyring = mock(Keyring.class);
        when(keyring.getPassword(anyString(), anyString())).thenAnswer(i -> {
            String v = entries.get(i.getArgument(0) + "|" + i.getArgument(1));
            if (v == null) {
                throw new PasswordAccessException("not found");
            }
            return v;
        });
        doAnswer(i -> entries.put(i.getArgument(0) + "|" + i.getArgument(1), i.getArgument(2)))
                .when(keyring).setPassword(anyString(), anyString(), anyString());
        doAnswer(i -> {
            if (entries.remove(i.getArgument(0) + "|" + i.getArgument(1)) == null) {
                throw new PasswordAccessException("not found");
            }
            return null;
        }).when(keyring).deletePassword(anyString(), anyString());
        store = new SecretStore();
        store.vault = () -> keyring;
    }

    @Test
    void removingAKeyAlsoRemovesItsOldJarvisEntrySoItDoesNotComeBack() {
        entries.put("Haris|anthropic", "sk-new");
        entries.put("JARVIS|anthropic", "sk-old");

        store.delete(SecretStore.ANTHROPIC);

        assertThat(entries).isEmpty();
        assertThat(store.has(SecretStore.ANTHROPIC)).isFalse();
        assertThat(entries).as("nothing was migrated back").isEmpty();
    }

    @Test
    void removingAKeyThatOnlyTheOldVersionStoredWorksToo() {
        entries.put("JARVIS|gemini", "g-old");

        store.delete(SecretStore.GEMINI);

        assertThat(store.has(SecretStore.GEMINI)).isFalse();
        assertThat(entries).isEmpty();
    }

    @Test
    void removingAnAbsentKeyIsFineAndTouchesNothingElse() {
        entries.put("Haris|colab", "token");

        store.delete(SecretStore.OPENAI_COMPATIBLE);

        assertThat(entries).containsOnlyKeys("Haris|colab");
    }

    @Test
    void anOldEntryIsStillMigratedWhenNothingWasRemoved() {
        entries.put("JARVIS|colab", "token");

        assertThat(store.get(SecretStore.COLAB)).contains("token");
        assertThat(entries).containsOnlyKeys("Haris|colab");
    }
}

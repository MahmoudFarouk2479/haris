package dev.haris.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import dev.haris.security.SecretStore;
import dev.haris.settings.SettingsController;
import dev.haris.settings.SettingsService;
import dev.haris.tools.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class MemoryStoreTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final byte[] key = randomKey();

    @TempDir
    Path tmp;

    private MemoryStore store(Path file, byte[] k) {
        return new MemoryStore(file, k::clone, json);
    }

    @Test
    void factsSurviveARestartAndAreEncryptedOnDisk() throws IOException {
        Path file = tmp.resolve("memory.enc");
        store(file, key).add("My sister Mona's birthday is 3 March", "person");
        store(file, key).add("Prefers replies in Egyptian Arabic", "preference");

        List<MemoryStore.Fact> facts = store(file, key).all();
        assertThat(facts).extracting(MemoryStore.Fact::text)
                .containsExactly("My sister Mona's birthday is 3 March", "Prefers replies in Egyptian Arabic");

        String onDisk = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertThat(onDisk).startsWith("JMEM1").doesNotContain("Mona").doesNotContain("Arabic").doesNotContain("person");
    }

    @Test
    void aTamperedFileIsRefusedNotTrusted() throws IOException {
        Path file = tmp.resolve("memory.enc");
        store(file, key).add("Likes tea", "preference");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 5] ^= 0x01;   // flip one bit, as a planted edit would
        Files.write(file, bytes);
        assertThatThrownBy(() -> store(file, key).all()).isInstanceOf(IOException.class)
                .hasMessageContaining("modified outside Haris");
    }

    @Test
    void anotherKeyCannotReadIt() throws IOException {
        Path file = tmp.resolve("memory.enc");
        store(file, key).add("Works at a bank", "fact");
        assertThatThrownBy(() -> store(file, randomKey()).all()).isInstanceOf(IOException.class);
    }

    @Test
    void duplicatesAreNotStoredTwiceAndForgettingWorks() throws IOException {
        MemoryStore s = store(tmp.resolve("m.enc"), key);
        MemoryStore.Fact a = s.add("Has a cat named Simba", "fact");
        MemoryStore.Fact b = s.add("has a cat   named simba", "fact");
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(s.search("cat", 5)).hasSize(1);
        assertThat(s.remove(a.id())).isTrue();
        assertThat(s.all()).isEmpty();
    }

    @Test
    void secretsAreNeverRemembered() {
        RememberTool tool = new RememberTool(mock(MemoryService.class));
        for (String secret : List.of("My password is hunter2", "The wifi pin is 4821", "Card 4111 1111 1111 1111",
                "API key sk-ant-abc123", "كلمة السر بتاعتي 1234", "my OTP code is 991")) {
            assertThatThrownBy(() -> tool.summarize(json.readTree("{\"fact\":\"" + secret + "\",\"category\":\"fact\"}")))
                    .as(secret).isInstanceOf(ToolException.class);
        }
    }

    @Test
    void theHudCannotOverwriteInternalSecrets() {
        SettingsController controller = new SettingsController(mock(SettingsService.class), mock(SecretStore.class),
                mock(dev.haris.llm.ProviderFactory.class));
        for (String internal : List.of(SecretStore.MEMORY_KEY, SecretStore.SEARXNG_SECRET)) {
            assertThatThrownBy(() -> controller.putSecret(internal, new SettingsController.SecretValue("attacker-key")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.deleteSecret(internal)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }
}

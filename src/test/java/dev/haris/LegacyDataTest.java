package dev.haris;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDataTest {

    @TempDir
    Path home;

    @Test
    void movesTheOldFolderAndRenamesTheDefaultName() throws IOException {
        Path legacy = Files.createDirectories(home.resolve(".jarvis"));
        Files.writeString(legacy.resolve("settings.json"), "{ \"assistantName\" : \"JARVIS\", \"userName\" : \"JARVIS fan\" }");
        Files.writeString(legacy.resolve("memory.enc"), "secret");

        LegacyData.migrate(legacy, home.resolve(".haris"));

        assertThat(legacy).doesNotExist();
        assertThat(home.resolve(".haris/memory.enc")).hasContent("secret");
        assertThat(home.resolve(".haris/settings.json"))
                .hasContent("{ \"assistantName\" : \"Haris\", \"userName\" : \"JARVIS fan\" }");
    }

    @Test
    void keepsANameTheUserChose() throws IOException {
        Path legacy = Files.createDirectories(home.resolve(".jarvis"));
        Files.writeString(legacy.resolve("settings.json"), "{\"assistantName\":\"Friday\"}");
        LegacyData.migrate(legacy, home.resolve(".haris"));
        assertThat(home.resolve(".haris/settings.json")).hasContent("{\"assistantName\":\"Friday\"}");
    }

    @Test
    void neverOverwritesExistingData() throws IOException {
        Path legacy = Files.createDirectories(home.resolve(".jarvis"));
        Files.writeString(legacy.resolve("memory.enc"), "old");
        Path current = Files.createDirectories(home.resolve(".haris"));
        Files.writeString(current.resolve("memory.enc"), "new");

        LegacyData.migrate(legacy, current);

        assertThat(current.resolve("memory.enc")).hasContent("new");
        assertThat(legacy.resolve("memory.enc")).hasContent("old");
    }
}

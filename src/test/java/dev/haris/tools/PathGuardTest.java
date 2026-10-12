package dev.haris.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.haris.settings.Settings;
import dev.haris.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathGuardTest {

    @TempDir
    Path tmp;

    Path granted;
    Path outside;
    PathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        granted = Files.createDirectory(tmp.resolve("granted")).toRealPath();
        outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
        Files.writeString(granted.resolve("notes.txt"), "hello");
        Files.writeString(outside.resolve("secret.txt"), "nope");

        SettingsService settings = mock(SettingsService.class);
        when(settings.get()).thenReturn(Settings.defaults().withFolders(List.of(granted.toString())));
        guard = new PathGuard(settings);
    }

    @Test
    void acceptsFilesInsideGrantedFolder() throws ToolException {
        assertThat(guard.existing(granted.resolve("notes.txt").toString())).isEqualTo(granted.resolve("notes.txt"));
        assertThat(guard.writable(granted.resolve("new.md").toString())).isEqualTo(granted.resolve("new.md"));
    }

    @Test
    void resolvesRelativePathsAgainstGrantedFolderByName() throws ToolException {
        assertThat(guard.existing("granted\\notes.txt")).isEqualTo(granted.resolve("notes.txt"));
    }

    @Test
    void rejectsAbsolutePathsOutside() {
        assertThatThrownBy(() -> guard.existing(outside.resolve("secret.txt").toString()))
                .isInstanceOf(ToolException.class).hasMessageContaining("Outside");
        assertThatThrownBy(() -> guard.writable(outside.resolve("x.txt").toString()))
                .isInstanceOf(ToolException.class).hasMessageContaining("Outside");
    }

    @Test
    void rejectsDotDotTraversal() {
        String sneaky = granted.resolve("..").resolve("outside").resolve("secret.txt").toString();
        assertThatThrownBy(() -> guard.existing(sneaky)).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> guard.writable(granted + "\\..\\outside\\planted.txt")).isInstanceOf(ToolException.class);
    }

    @Test
    void rejectsSymlinkEscapes() throws IOException {
        Path link = granted.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links need Developer Mode or admin on Windows");
        }
        assertThatThrownBy(() -> guard.existing(link.resolve("secret.txt").toString()))
                .isInstanceOf(ToolException.class).hasMessageContaining("Outside");
        assertThatThrownBy(() -> guard.writable(link.resolve("planted.txt").toString()))
                .isInstanceOf(ToolException.class).hasMessageContaining("Outside");
    }

    @Test
    void refusesSecretsEvenInsideGrantedFolder() throws IOException {
        for (String name : List.of(".env", "id_rsa", "vault.kdbx", "server.pem", "my-passwords.txt", "aws_credentials.json")) {
            Files.writeString(granted.resolve(name), "x");
            assertThatThrownBy(() -> guard.existing(granted.resolve(name).toString()))
                    .as(name).isInstanceOf(ToolException.class);
        }
    }

    @Test
    void refusesToWriteExecutables() {
        for (String name : List.of("run.exe", "a.bat", "b.cmd", "c.ps1", "d.vbs", "e.lnk", "f.js", "g.reg", "h.exe.", "i.EXE")) {
            assertThatThrownBy(() -> guard.writable(granted.resolve(name).toString()))
                    .as(name).isInstanceOf(ToolException.class);
        }
    }

    @Test
    void refusesHiddenFilesUncPathsAndAlternateStreams() {
        assertThatThrownBy(() -> guard.writable(granted.resolve(".hidden").toString())).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> guard.existing("\\\\server\\share\\file.txt")).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> guard.writable(granted.resolve("notes.txt") + ":evil")).isInstanceOf(ToolException.class);
    }
}

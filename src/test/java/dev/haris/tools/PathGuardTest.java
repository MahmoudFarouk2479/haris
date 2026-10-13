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

    @Test
    void writesOnlyTextFileTypes() throws ToolException {
        for (String name : List.of("x.pyw", "x.msc", "x", "x.html", "x.library-ms", "x.pyw.")) {
            assertThatThrownBy(() -> guard.writableTextFile(granted.resolve(name).toString()))
                    .as(name).isInstanceOf(ToolException.class);
        }
        assertThat(guard.writableTextFile(granted.resolve("x.md").toString())).isEqualTo(granted.resolve("x.md"));
        assertThat(guard.writableTextFile(granted.resolve("x.MD").toString())).isEqualTo(granted.resolve("x.MD"));
    }

    @Test
    void renamesKeepTheFileType() throws IOException, ToolException {
        Path photo = Files.writeString(granted.resolve("a.jpg"), "x");
        Path notes = granted.resolve("notes.txt");
        assertThat(guard.moveTarget(photo, granted.resolve("b.JPG").toString())).isEqualTo(granted.resolve("b.JPG"));
        assertThat(guard.moveTarget(notes, granted.resolve("notes.md").toString())).isEqualTo(granted.resolve("notes.md"));
        assertThatThrownBy(() -> guard.moveTarget(notes, granted.resolve("notes.pyw").toString()))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> guard.moveTarget(notes, granted.resolve("notes.jpg").toString()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void foldersWithDotsCanStillBeRenamed() throws IOException, ToolException {
        Path folder = Files.createDirectory(granted.resolve("v1.2"));
        assertThat(guard.moveTarget(folder, granted.resolve("v1.3").toString())).isEqualTo(granted.resolve("v1.3"));
        assertThatThrownBy(() -> guard.moveTarget(folder,
                granted.resolve("x.{645FF040-5081-101B-9F08-00AA002F954E}").toString()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void refusesShellNamespaceFoldersWhoseNamesEndInDots() throws IOException {
        // Windows drops trailing dots when it stores a name, so "x.{CLSID}." becomes "x.{CLSID}".
        String clsid = "x.{645FF040-5081-101B-9F08-00AA002F954E}";
        for (String name : List.of(clsid + ".", clsid + "...", clsid + ". .")) {
            assertThatThrownBy(() -> guard.writableFolder(granted.resolve(name).toString()))
                    .as(name).isInstanceOf(ToolException.class);
        }
        Path folder = Files.createDirectory(granted.resolve("v2.0"));
        assertThatThrownBy(() -> guard.moveTarget(folder, granted.resolve(clsid + ".").toString()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void refusesShellNamespaceFolders() throws ToolException {
        assertThatThrownBy(() -> guard.writableFolder(
                granted.resolve("x.{645FF040-5081-101B-9F08-00AA002F954E}").toString()))
                .isInstanceOf(ToolException.class);
        assertThat(guard.writableFolder(granted.resolve("Projects").toString())).isEqualTo(granted.resolve("Projects"));
    }

    @Test
    void refusesScriptAndShellTypesThatWereMissing() {
        for (String name : List.of("a.py", "a.pyw", "a.pyc", "a.pyz", "a.msc", "a.wsc", "a.sct", "a.chm",
                "a.library-ms", "a.searchConnector-ms", "a.settingcontent-ms", "a.website", "a.theme",
                "a.themepack", "a.xll", "a.xlam", "a.docm", "a.xlsm", "a.pptm", "a.iso", "a.img", "a.vhd", "a.vhdx")) {
            assertThatThrownBy(() -> guard.writable(granted.resolve(name).toString()))
                    .as(name).isInstanceOf(ToolException.class);
        }
    }
}

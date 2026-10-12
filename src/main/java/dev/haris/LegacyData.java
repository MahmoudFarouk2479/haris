package dev.haris;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * This project used to be called JARVIS. On the first start after the rename, the old data folder
 * (~/.jarvis: settings, encrypted memory, audit log, reminders, the HUD's browser profile) becomes
 * ~/.haris, so nothing the user set up is lost. Stored keys are migrated by SecretStore on first read.
 * Runs before Spring starts, because the log file already lives in the new folder.
 */
final class LegacyData {

    private LegacyData() {
    }

    static void migrate() {
        Path home = Path.of(System.getProperty("user.home"));
        migrate(home.resolve(".jarvis"), home.resolve(".haris"));
    }

    static void migrate(Path legacy, Path current) {
        if (!Files.isDirectory(legacy) || Files.exists(current)) {
            return;
        }
        try {
            Files.move(legacy, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Usually a file still open in the old app. Start fresh rather than half-move.
            System.err.println("Could not move " + legacy + " to " + current + ": " + e.getMessage());
            return;
        }
        // The old default name: becomes the new default. A name the user chose stays.
        Path settings = current.resolve("settings.json");
        try {
            if (Files.isRegularFile(settings)) {
                String json = Files.readString(settings, StandardCharsets.UTF_8);
                String renamed = json.replaceFirst("(\"assistantName\"\\s*:\\s*)\"JARVIS\"", "$1\"Haris\"");
                if (!renamed.equals(json)) {
                    Files.writeString(settings, renamed, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not update " + settings + ": " + e.getMessage());
        }
    }
}

package dev.haris;

import java.io.IOException;
import java.time.Duration;

import dev.haris.launcher.HudWindow;
import dev.haris.launcher.SingleInstance;
import dev.haris.settings.SettingsService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class HarisApplication {

    public static void main(String[] args) {
        LegacyData.migrate();   // ~/.jarvis -> ~/.haris, once, before the log opens there
        try {
            // One Haris per user: a second launch brings the running window forward and exits.
            if (SingleInstance.claim(SettingsService.dataDir(), HudWindow::bringToFront,
                    Duration.ofSeconds(45), Duration.ofMinutes(3)).isEmpty()) {
                return;
            }
        } catch (IOException e) {
            System.err.println("Single-instance check skipped: " + e.getMessage());   // never a reason not to start
        }
        // Not headless: the clipboard, the Recycle Bin and tray notifications need the desktop.
        new SpringApplicationBuilder(HarisApplication.class).headless(false).run(args);
    }
}

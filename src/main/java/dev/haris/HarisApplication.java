package dev.haris;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class HarisApplication {

    public static void main(String[] args) {
        LegacyData.migrate();   // ~/.jarvis -> ~/.haris, once, before the log opens there
        // Not headless: the clipboard, the Recycle Bin and tray notifications need the desktop.
        new SpringApplicationBuilder(HarisApplication.class).headless(false).run(args);
    }
}

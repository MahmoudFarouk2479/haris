package dev.haris.launcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import dev.haris.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Keeps the HUD on the same loopback port across launches (picked at random once, stored in
 * ~/.haris/port). The browser remembers microphone permission per origin — and the origin
 * includes the port — so without this the user would be asked for the microphone every time.
 * The session token still changes on every launch.
 */
@Component
public class StablePort implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(StablePort.class);
    private static final int MIN = 41_000;
    private static final int MAX = 59_000;

    private final int configuredPort;

    public StablePort(@Value("${server.port:0}") int configuredPort) {
        this.configuredPort = configuredPort;
    }

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        if (configuredPort != 0) {
            return;   // an explicit port always wins
        }
        Path file = SettingsService.dataDir().resolve("port");
        int port = read(file);
        if (port == 0 || !free(port)) {
            port = pickFree();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Integer.toString(port));
            } catch (IOException e) {
                log.debug("Could not remember the port: {}", e.getMessage());
            }
        }
        factory.setPort(port);
    }

    private static int read(Path file) {
        try {
            int p = Integer.parseInt(Files.readString(file).strip());
            return p >= MIN && p <= MAX ? p : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static int pickFree() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 50; i++) {
            int p = MIN + random.nextInt(MAX - MIN);
            if (free(p)) {
                return p;
            }
        }
        return 0;   // let the OS choose
    }

    private static boolean free(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

package dev.haris.system;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Map;

import dev.haris.agent.EventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shows a Windows notification and tells every open HUD window. */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final EventHub events;
    private TrayIcon tray;

    public Notifier(EventHub events) {
        this.events = events;
    }

    public void notify(String title, String message) {
        events.publish("notification", Map.of("title", title, "message", message));
        try {
            TrayIcon icon = trayIcon();
            if (icon != null) {
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            }
        } catch (RuntimeException e) {
            log.debug("Tray notification failed: {}", e.getMessage());
        }
    }

    private synchronized TrayIcon trayIcon() {
        if (tray != null || !SystemTray.isSupported()) {
            return tray;
        }
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(56, 217, 255));
        g.fillOval(4, 4, 24, 24);
        g.setColor(new Color(2, 6, 12));
        g.fillOval(10, 10, 12, 12);
        g.dispose();
        TrayIcon icon = new TrayIcon(img, "Haris");
        icon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(icon);
            tray = icon;
        } catch (AWTException e) {
            log.debug("No system tray: {}", e.getMessage());
        }
        return tray;
    }
}

package dev.haris.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.haris.agent.EventHub;
import dev.haris.security.SessionToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens the HUD as a chrome-less app window in Edge (or Chrome), using a private browser
 * profile under ~/.haris so it shares no cookies, extensions or history with the user's
 * everyday browser. The session token rides in the URL fragment, which the browser never
 * sends over the wire. The command line is an argument list, never a shell string.
 */
@Component
public class AppWindowLauncher {

    private static final Logger log = LoggerFactory.getLogger(AppWindowLauncher.class);

    private final SessionToken token;
    private final ConfigurableApplicationContext context;
    private final HudWindow hudWindow;
    private final boolean openWindow;
    private final boolean exitOnClose;

    public AppWindowLauncher(SessionToken token, ConfigurableApplicationContext context, HudWindow hudWindow,
                             @Value("${haris.open-window:true}") boolean openWindow,
                             @Value("${haris.exit-on-window-close:true}") boolean exitOnClose) {
        this.token = token;
        this.context = context;
        this.hudWindow = hudWindow;
        this.openWindow = openWindow;
        this.exitOnClose = exitOnClose;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        String url = "http://127.0.0.1:" + token.port() + "/#token=" + token.value();
        if (!openWindow) {
            log.info("HUD ready. Open this address in a browser on this machine:\n  {}", url);
            return;
        }

        Optional<Path> browser = findBrowser();
        if (browser.isEmpty()) {
            log.warn("Neither Edge nor Chrome found. Open this address in a browser on this machine:\n  {}", url);
            return;
        }

        List<String> cmd = new ArrayList<>(List.of(
                browser.get().toString(),
                "--app=" + url,
                "--user-data-dir=" + HudWindow.profile(),
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-sync",
                "--disable-features=Translate,msEdgeSidebarV2",
                // Haris speaks after "Hey Haris" without any click; this window (its own isolated
                // profile, serving only Haris's page) may therefore play audio without a gesture.
                "--autoplay-policy=no-user-gesture-required",
                "--window-size=1440,900"));
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            hudWindow.setProcess(process);
            // A second launch brings this window forward (it finds the browser by its profile; this
            // process is only a fallback, as it may hand the window to an Edge already running it).
            SingleInstance.windowOpened(process.pid(),
                    process.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L));
            log.info("Haris HUD opened on 127.0.0.1:{}", token.port());
            // Not process.onExit(): when an Edge process for this profile already exists, the one we
            // start hands the window over and exits at once. Shutdown follows the HUD's own
            // connection instead (EventHub.HudClosed).
        } catch (IOException e) {
            log.warn("Could not open the HUD window ({}). Open this address manually:\n  {}", e.getMessage(), url);
        }
    }

    /** The last HUD window has been closed (and stayed closed): shut the core and its engines down. */
    @EventListener
    public void onHudClosed(EventHub.HudClosed event) {
        if (!exitOnClose) {
            return;
        }
        log.info("HUD window closed — shutting the core down.");
        SingleInstance.closing();   // a launch from now on waits for this exit instead of looking for the window
        Thread.ofPlatform().name("shutdown").start(() -> System.exit(SpringApplication.exit(context, () -> 0)));
    }

    private static Optional<Path> findBrowser() {
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
        return List.of(
                        Path.of(pf86, "Microsoft", "Edge", "Application", "msedge.exe"),
                        Path.of(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
                        Path.of(pf, "Google", "Chrome", "Application", "chrome.exe"),
                        Path.of(pf86, "Google", "Chrome", "Application", "chrome.exe"),
                        Path.of(local, "Google", "Chrome", "Application", "chrome.exe"))
                .stream()
                .filter(Files::isRegularFile)
                .findFirst();
    }
}

package dev.haris.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import dev.haris.agent.AuditLog;
import dev.haris.settings.SettingsService;
import dev.haris.tools.Risk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Start with Windows": one per-user Run entry pointing at the installed Haris.exe, written
 * only when the user ticks the box in Settings. No tool can change it, it needs no admin
 * rights, and unticking the box (or uninstalling) removes it.
 */
@RestController
public class AutostartService {

    private static final Logger log = LoggerFactory.getLogger(AutostartService.class);
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE = "Haris";
    private static final String LEGACY_VALUE = "JARVIS";

    private final SettingsService settings;
    private final AuditLog audit;

    public AutostartService(SettingsService settings, AuditLog audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** The launcher jpackage created; absent when running from source. */
    static Optional<Path> installedExe() {
        String p = System.getProperty("jpackage.app-path");
        return p == null ? Optional.empty() : Optional.of(Path.of(p)).filter(Files::isRegularFile);
    }

    @GetMapping("/api/system/autostart")
    public Map<String, Object> status() {
        return Map.of("available", isWindows() && installedExe().isPresent(), "enabled", current().isPresent());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        apply();
    }

    @EventListener
    public void onSettingsChanged(SettingsService.SettingsChanged event) {
        apply();
    }

    private synchronized void apply() {
        if (!isWindows()) {
            return;
        }
        boolean wanted = Boolean.TRUE.equals(settings.get().startWithWindows());
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, LEGACY_VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, LEGACY_VALUE);
            }
        } catch (RuntimeException e) {
            log.debug("Could not remove the old JARVIS Run entry: {}", e.getMessage());
        }
        Optional<String> existing = current();
        Optional<Path> exe = installedExe();
        try {
            if (wanted && exe.isPresent()) {
                String command = "\"" + exe.get() + "\"";
                if (!existing.filter(command::equals).isPresent()) {
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, command);
                    audit.record("autostart", "Start with Windows enabled", Risk.SAFE, "user", "ok");
                }
            } else if (!wanted && existing.isPresent()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE);
                audit.record("autostart", "Start with Windows disabled", Risk.SAFE, "user", "ok");
            }
        } catch (RuntimeException e) {
            log.warn("Could not update the Windows Run entry: {}", e.getMessage());
        }
    }

    private static Optional<String> current() {
        try {
            if (isWindows() && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) {
                return Optional.of(Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE));
            }
        } catch (RuntimeException e) {
            // unreadable: treat as absent
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

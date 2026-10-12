package dev.haris.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.haris.settings.SettingsService;
import dev.haris.system.Notifier;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reminders that fire while Haris is running, as a Windows notification and in the HUD.
 * Deliberately not registered with Task Scheduler: the app leaves nothing behind in the
 * system that keeps running after it is closed or uninstalled.
 */
@Component
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final int MAX_REMINDERS = 100;

    public record Reminder(String id, String text, long dueEpochMs) {}

    private final JsonMapper json;
    private final Notifier notifier;
    private final Path file = SettingsService.dataDir().resolve("reminders.json");
    private final Map<String, Reminder> reminders = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reminders");
        t.setDaemon(true);
        return t;
    });

    public ReminderService(JsonMapper json, Notifier notifier) {
        this.json = json;
        this.notifier = notifier;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<Reminder> saved = json.readValue(Files.readString(file), new TypeReference<List<Reminder>>() {});
            long now = System.currentTimeMillis();
            for (Reminder r : saved) {
                if (r.dueEpochMs() > now - 3_600_000) {   // missed by less than an hour: still deliver
                    schedule(r);
                }
            }
            persist();
        } catch (Exception e) {
            log.warn("Could not load reminders: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    public synchronized Reminder add(String text, Instant due) {
        if (reminders.size() >= MAX_REMINDERS) {
            throw new IllegalStateException("Too many pending reminders.");
        }
        Reminder r = new Reminder(UUID.randomUUID().toString().substring(0, 8), text, due.toEpochMilli());
        schedule(r);
        persist();
        return r;
    }

    public synchronized boolean cancel(String id) {
        Reminder r = reminders.remove(id);
        ScheduledFuture<?> f = scheduled.remove(id);
        if (f != null) {
            f.cancel(false);
        }
        persist();
        return r != null;
    }

    public List<Reminder> pending() {
        return reminders.values().stream().sorted(Comparator.comparingLong(Reminder::dueEpochMs)).toList();
    }

    private void schedule(Reminder r) {
        reminders.put(r.id(), r);
        long delay = Math.max(0, r.dueEpochMs() - System.currentTimeMillis());
        scheduled.put(r.id(), scheduler.schedule(() -> fire(r), delay, TimeUnit.MILLISECONDS));
    }

    private synchronized void fire(Reminder r) {
        reminders.remove(r.id());
        scheduled.remove(r.id());
        persist();
        notifier.notify("Reminder", r.text());
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("reminders.json.tmp");
            Files.writeString(tmp, json.writeValueAsString(new ArrayList<>(reminders.values())));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not save reminders: {}", e.getMessage());
        }
    }
}

package cn.oneachina.captureSpawn.logging;

import org.bukkit.plugin.Plugin;
import cn.oneachina.captureSpawn.scheduler.ScheduledHandle;
import cn.oneachina.captureSpawn.scheduler.SchedulerFacade;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class BallLogService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Plugin plugin;
    private final SchedulerFacade scheduler;
    private final BlockingQueue<BallLogEntry> queue;
    private ScheduledHandle flushTask;

    public BallLogService(Plugin plugin, SchedulerFacade scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        int maxQueue = Math.max(1000, plugin.getConfig().getInt("logging.max-queue", 10000));
        this.queue = new LinkedBlockingQueue<>(maxQueue);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("logging.enabled", true)) {
            return;
        }
        int interval = Math.max(1, plugin.getConfig().getInt("logging.flush-interval-ticks", 20));
        this.flushTask = scheduler.runAsyncTimer(interval, interval, ignored -> flushOnce());
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushOnce();
    }

    public void log(BallLogEntry entry) {
        if (entry == null) {
            return;
        }
        if (!plugin.getConfig().getBoolean("logging.enabled", true)) {
            return;
        }
        queue.offer(entry);
    }

    public List<BallLogEntry> query(String playerName, long sinceMillis, long untilMillis, String actionFilter, int limit) {
        if (!plugin.getConfig().getBoolean("logging.enabled", true)) {
            return List.of();
        }
        int maxLimit = Math.max(1, Math.min(500, limit));
        String normalizedAction = actionFilter == null ? "" : actionFilter.trim().toUpperCase();
        String normalizedPlayer = playerName == null ? "" : playerName.trim();

        List<LocalDate> days = daysBetween(sinceMillis, untilMillis);
        List<BallLogEntry> out = new ArrayList<>(Math.min(50, maxLimit));
        for (int i = days.size() - 1; i >= 0; i--) {
            File f = resolveLogFile(days.get(i));
            if (!f.exists()) {
                continue;
            }
            for (String line : ReverseLineReader.readLastLines(f, 5000)) {
                BallLogEntry e = BallLogEntry.parse(line);
                if (e == null) {
                    continue;
                }
                if (e.timeMillis() < sinceMillis || e.timeMillis() > untilMillis) {
                    continue;
                }
                if (!normalizedAction.isBlank() && !matchesAction(normalizedAction, e.action())) {
                    continue;
                }
                if (!normalizedPlayer.isBlank() && !e.playerName().equalsIgnoreCase(normalizedPlayer)) {
                    continue;
                }
                out.add(e);
                if (out.size() >= maxLimit) {
                    return out;
                }
            }
        }
        return out;
    }

    private void flushOnce() {
        try {
            List<BallLogEntry> batch = new ArrayList<>(256);
            queue.drainTo(batch, 2000);
            if (batch.isEmpty()) {
                return;
            }
            for (BallLogEntry e : batch) {
                append(e);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to flush logs: " + ex.getMessage());
        }
    }

    private void append(BallLogEntry entry) {
        File file = resolveLogFile(LocalDate.ofInstant(Instant.ofEpochMilli(entry.timeMillis()), ZoneId.systemDefault()));
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            w.write(entry.toLine());
            w.newLine();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to write log line: " + ex.getMessage());
        }
    }

    private File resolveLogFile(LocalDate day) {
        String name = plugin.getConfig().getString("logging.file", "logs/ball-%DATE%.log");
        if (name == null || name.isBlank()) {
            name = "logs/ball-%DATE%.log";
        }
        name = name.replace("%DATE%", DAY.format(day));
        return new File(plugin.getDataFolder(), name);
    }

    private static List<LocalDate> daysBetween(long sinceMillis, long untilMillis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = Instant.ofEpochMilli(sinceMillis).atZone(zone).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(untilMillis).atZone(zone).toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        LocalDate cur = start;
        while (!cur.isAfter(end) && days.size() < 60) {
            days.add(cur);
            cur = cur.plusDays(1);
        }
        return days;
    }

    private static boolean matchesAction(String filter, String action) {
        if (action == null) {
            return false;
        }
        String a = action.toUpperCase();
        if (filter.equals("ALL")) {
            return true;
        }
        if (filter.equals("RELEASE")) {
            return a.contains("RELEASE");
        }
        if (filter.equals("CAPTURE")) {
            return a.contains("CAPTURE");
        }
        return a.equals(filter);
    }
}

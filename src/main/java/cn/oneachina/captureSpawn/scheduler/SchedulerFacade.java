package cn.oneachina.captureSpawn.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SchedulerFacade {
    private final Plugin plugin;
    private final boolean folia;

    public SchedulerFacade(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = detectFolia();
    }

    public boolean isFolia() {
        return folia;
    }

    public ScheduledHandle runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (folia) {
            ScheduledTask scheduled = plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> task.run());
            return wrap(scheduled);
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTask(plugin, task);
        return wrap(scheduled);
    }

    public ScheduledHandle runGlobalDelayed(long delayTicks, Runnable task) {
        Objects.requireNonNull(task, "task");
        long safeDelay = Math.max(0L, delayTicks);
        if (folia) {
            ScheduledTask scheduled = plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), safeDelay);
            return wrap(scheduled);
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
        return wrap(scheduled);
    }

    public ScheduledHandle runGlobalTimer(long delayTicks, long periodTicks, Consumer<ScheduledHandle> tick) {
        Objects.requireNonNull(tick, "tick");
        long safeDelay = Math.max(0L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (folia) {
            long foliaDelay = Math.max(1L, safeDelay);
            final ScheduledHandle[] handleRef = new ScheduledHandle[1];
            ScheduledTask scheduled = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin,
                    task -> tick.accept(handleRef[0]),
                    foliaDelay,
                    safePeriod
            );
            handleRef[0] = wrap(scheduled);
            return handleRef[0];
        }
        final ScheduledHandle[] handleRef = new ScheduledHandle[1];
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick.accept(handleRef[0]), safeDelay, safePeriod);
        handleRef[0] = wrap(scheduled);
        return handleRef[0];
    }

    public ScheduledHandle runAsyncTimer(long delayTicks, long periodTicks, Consumer<ScheduledHandle> tick) {
        Objects.requireNonNull(tick, "tick");
        long safeDelay = Math.max(0L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (folia) {
            final ScheduledHandle[] handleRef = new ScheduledHandle[1];
            ScheduledTask scheduled = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin,
                    task -> tick.accept(handleRef[0]),
                    ticksToMillis(safeDelay),
                    ticksToMillis(safePeriod),
                    TimeUnit.MILLISECONDS
            );
            handleRef[0] = wrap(scheduled);
            return handleRef[0];
        }
        final ScheduledHandle[] handleRef = new ScheduledHandle[1];
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> tick.accept(handleRef[0]), safeDelay, safePeriod);
        handleRef[0] = wrap(scheduled);
        return handleRef[0];
    }

    public ScheduledHandle runOnEntity(Entity entity, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (entity == null || !entity.isValid()) {
            return NoOpScheduledHandle.INSTANCE;
        }
        if (folia) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), null);
            return wrap(scheduled);
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTask(plugin, task);
        return wrap(scheduled);
    }

    public ScheduledHandle runEntityTimer(Entity entity, long delayTicks, long periodTicks, Consumer<ScheduledHandle> tick) {
        Objects.requireNonNull(tick, "tick");
        if (entity == null || !entity.isValid()) {
            return NoOpScheduledHandle.INSTANCE;
        }
        long safeDelay = Math.max(0L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (folia) {
            long foliaDelay = Math.max(1L, safeDelay);
            final ScheduledHandle[] handleRef = new ScheduledHandle[1];
            ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
                    plugin,
                    task -> tick.accept(handleRef[0]),
                    null,
                    foliaDelay,
                    safePeriod
            );
            handleRef[0] = wrap(scheduled);
            return handleRef[0];
        }
        return runGlobalTimer(safeDelay, safePeriod, tick);
    }

    public ScheduledHandle runAtLocation(Location location, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (location == null || location.getWorld() == null) {
            return NoOpScheduledHandle.INSTANCE;
        }
        if (folia) {
            ScheduledTask scheduled = plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> task.run());
            return wrap(scheduled);
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTask(plugin, task);
        return wrap(scheduled);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static long ticksToMillis(long ticks) {
        return Math.max(1L, ticks) * 50L;
    }

    private static ScheduledHandle wrap(BukkitTask task) {
        return new ScheduledHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    private static ScheduledHandle wrap(ScheduledTask task) {
        return new ScheduledHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    private enum NoOpScheduledHandle implements ScheduledHandle {
        INSTANCE;

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}

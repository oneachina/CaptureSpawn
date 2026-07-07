package cn.oneachina.captureSpawn.listener;

import cn.oneachina.captureSpawn.CaptureSpawn;
import cn.oneachina.captureSpawn.item.BallData;
import cn.oneachina.captureSpawn.item.BallItemService;
import cn.oneachina.captureSpawn.item.ItemFactory;
import cn.oneachina.captureSpawn.logging.BallLogEntry;
import cn.oneachina.captureSpawn.logging.BallLogService;
import cn.oneachina.captureSpawn.nbt.NbtApiBridge;
import cn.oneachina.captureSpawn.nbt.NbtPayloadCodec;
import cn.oneachina.captureSpawn.protection.ProtectionHooks;
import cn.oneachina.captureSpawn.scheduler.SchedulerFacade;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

public final class BallDropReleaseListener implements Listener {
    private final CaptureSpawn plugin;
    private final BallItemService ballItemService;
    private final ItemFactory itemFactory;
    private final NbtApiBridge nbtBridge;
    private final BallLogService logService;
    private final SchedulerFacade scheduler;

    public BallDropReleaseListener(CaptureSpawn plugin, BallItemService ballItemService, ItemFactory itemFactory, NbtApiBridge nbtBridge, BallLogService logService, SchedulerFacade scheduler) {
        this.plugin = plugin;
        this.ballItemService = ballItemService;
        this.itemFactory = itemFactory;
        this.nbtBridge = nbtBridge;
        this.logService = logService;
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getConfig().getBoolean("release.drop.enabled", true)) {
            return;
        }
        Item itemEntity = event.getItemDrop();
        ItemStack stack = itemEntity.getItemStack();
        BallData data = ballItemService.read(stack);
        if (data == null || !data.captured()) {
            return;
        }
        if (plugin.getConfig().getBoolean("release.require-permission", true)
                && !event.getPlayer().hasPermission(resolvePerm("permissions.release", "capturespawn.release"))) {
            return;
        }

        int maxWait = Math.max(1, plugin.getConfig().getInt("release.drop.max-wait-ticks", 40));
        final int[] ticks = {0};
        scheduler.runEntityTimer(itemEntity, 1L, 1L, handle -> {
            ticks[0]++;
            if (!itemEntity.isValid() || itemEntity.isDead()) {
                handle.cancel();
                return;
            }
            boolean requireOnGround = plugin.getConfig().getBoolean("release.drop.require-on-ground", true);
            if (requireOnGround && !itemEntity.isOnGround() && ticks[0] < maxWait) {
                return;
            }

            Location base = itemEntity.getLocation();
            Location safe = findSafeReleaseLocation(base);
            if (safe == null) {
                logService.log(BallLogEntry.of(playerRef(event.getPlayer()), "DROP_RELEASE", data.entityType(), base, "FAIL", "no_safe_location"));
                handle.cancel();
                return;
            }

            Player player = event.getPlayer();
            if (!isReleaseAllowed(player, safe)) {
                send(player, "messages.release.spawn-denied", "&c由于权限原因生成失败（检查领地设置是否开启允许自定义怪物生成）。");
                logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "DENIED", "protection"));
                handle.cancel();
                return;
            }

            EntityType type;
            try {
                type = EntityType.valueOf(data.entityType());
            } catch (Exception ex) {
                logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "FAIL", "invalid_entity_type"));
                handle.cancel();
                return;
            }

            Entity spawned;
            try {
                spawned = safe.getWorld().spawnEntity(safe, type);
            } catch (Exception ex) {
                logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "FAIL", "spawn_failed"));
                handle.cancel();
                return;
            }
            if (!spawned.isValid() || spawned.isDead()) {
                send(player, "messages.release.spawn-denied", "&c由于权限原因生成失败（检查领地设置是否开启允许自定义怪物生成）。");
                logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "DENIED", "spawn_denied"));
                handle.cancel();
                return;
            }

            String snbt = NbtPayloadCodec.decodeToSnbt(data.entityNbt());
            if (nbtBridge.loadFromSnbt(spawned, snbt, player)) {
                spawned.remove();
                logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "FAIL", "nbt_failed"));
                handle.cancel();
                return;
            }

            boolean consume = plugin.getConfig().getBoolean("release.consume-filled", false);
            if (consume) {
                itemEntity.remove();
            } else {
                ItemStack empty = itemFactory.createEmptyBall();
                empty.setAmount(stack.getAmount());
                itemEntity.setItemStack(empty);
            }
            logService.log(BallLogEntry.of(playerRef(player), "DROP_RELEASE", data.entityType(), safe, "SUCCESS", ""));
            handle.cancel();
        });
    }

    private Location findSafeReleaseLocation(Location base) {
        if (base == null || base.getWorld() == null) {
            return null;
        }
        Location snapped = snapAboveGround(base);
        for (int dy = 0; dy <= 4; dy++) {
            Location feet = snapped.clone().add(0, dy, 0);
            Block feetBlock = feet.getWorld().getBlockAt(feet);
            Block headBlock = feet.getWorld().getBlockAt(feet.clone().add(0, 1, 0));
            Block floor = feet.getWorld().getBlockAt(feet.clone().add(0, -1, 0));
            if (feetBlock.isPassable() && headBlock.isPassable() && !floor.isPassable()) {
                return feet.add(0, 0.22, 0);
            }
        }
        return snapped.add(0, 0.22, 0);
    }

    private static Location snapAboveGround(Location loc) {
        if (loc.getWorld() == null) {
            return loc;
        }
        Location probeStart = loc.clone().add(0, 0.6, 0);
        org.bukkit.util.RayTraceResult down = loc.getWorld().rayTraceBlocks(
                probeStart,
                new org.bukkit.util.Vector(0, -1, 0),
                2.0,
                org.bukkit.FluidCollisionMode.NEVER,
                true
        );
        if (down != null && down.getHitPosition() != null) {
            return down.getHitPosition().toLocation(loc.getWorld()).add(0, 0.03, 0);
        }
        return loc.clone().add(0, 0.03, 0);
    }

    private boolean isReleaseAllowed(Player player, Location loc) {
        if (player == null || loc == null || loc.getWorld() == null) {
            return false;
        }
        if (!plugin.getConfig().getBoolean("protection.enabled", true)) {
            return true;
        }
        boolean requireBuild = plugin.getConfig().getBoolean("protection.release-requires-build", false);
        return ProtectionHooks.canRelease(player, loc, requireBuild);
    }

    private void send(Player player, String path, String fallback) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String msg = plugin.getConfig().getString(path, fallback);
        if (msg == null || msg.isBlank()) {
            return;
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    private static BallLogEntry.PlayerRef playerRef(Player player) {
        if (player == null) {
            return new BallLogEntry.PlayerRef(null, "");
        }
        return new BallLogEntry.PlayerRef(player.getUniqueId(), player.getName());
    }

    private String resolvePerm(String path, String fallback) {
        String perm = plugin.getConfig().getString(path, fallback);
        if (perm == null || perm.isBlank()) {
            return fallback;
        }
        return perm;
    }
}

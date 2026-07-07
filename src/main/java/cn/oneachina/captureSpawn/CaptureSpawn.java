package cn.oneachina.captureSpawn;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import cn.oneachina.captureSpawn.command.CaptureSpawnCommand;
import cn.oneachina.captureSpawn.format.EntityInfoFormatter;
import cn.oneachina.captureSpawn.item.BallItemService;
import cn.oneachina.captureSpawn.item.ItemFactory;
import cn.oneachina.captureSpawn.item.Keys;
import cn.oneachina.captureSpawn.item.RecipeManager;
import cn.oneachina.captureSpawn.listener.BallBlockPlaceListener;
import cn.oneachina.captureSpawn.listener.BallDropReleaseListener;
import cn.oneachina.captureSpawn.listener.CraftPermissionListener;
import cn.oneachina.captureSpawn.listener.DirectInteractListener;
import cn.oneachina.captureSpawn.logging.BallLogService;
import cn.oneachina.captureSpawn.nbt.NbtApiBridge;
import cn.oneachina.captureSpawn.protection.ProtectionHooks;
import cn.oneachina.captureSpawn.protection.ResidenceFlagListener;
import cn.oneachina.captureSpawn.scheduler.SchedulerFacade;
import cn.oneachina.captureSpawn.throwing.BallThrower;
import cn.oneachina.captureSpawn.throwing.PacketEventsThrowListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class CaptureSpawn extends JavaPlugin {
    private Keys keys;
    private ItemFactory itemFactory;
    private NamespacedKey emptyBallRecipeKey;
    private NbtApiBridge nbtApiBridge;
    private PacketListenerCommon packetEventsThrowListener;
    private BallLogService logService;
    private SchedulerFacade schedulerFacade;
    public static CaptureSpawn instance;

    private final Set<UUID> debugPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ProtectionHooks.init(this);

        this.keys = new Keys(this);
        this.itemFactory = new ItemFactory(this, keys);
        this.nbtApiBridge = new NbtApiBridge();
        this.schedulerFacade = new SchedulerFacade(this);
        this.logService = new BallLogService(this, schedulerFacade);
        this.logService.start();

        if (getCommand("capturespawn") != null) {
            CaptureSpawnCommand cmd = new CaptureSpawnCommand(this, logService);
            Objects.requireNonNull(getCommand("capturespawn")).setExecutor(cmd);
            Objects.requireNonNull(getCommand("capturespawn")).setTabCompleter(cmd);
        }
        registerRuntimeComponents();

        instance = this;
    }

    @Override
    public void onDisable() {
        unregisterPacketEventsListener();
        if (logService != null) {
            logService.stop();
        }
    }

    public boolean reloadPlugin() {
        try {
            reloadConfig();
            ProtectionHooks.init(this);
            unregisterPacketEventsListener();
            HandlerList.unregisterAll(this);
            if (logService != null) {
                logService.stop();
                logService.start();
            }
            registerRuntimeComponents();
            return true;
        } catch (Exception ex) {
            getLogger().severe("Reload failed: " + ex.getMessage());
            return false;
        }
    }

    private void registerRuntimeComponents() {
        RecipeManager recipeManager = new RecipeManager(this, itemFactory);
        this.emptyBallRecipeKey = recipeManager.registerEmptyBallRecipe();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ResidenceFlagListener(this, schedulerFacade), this);
        pm.registerEvents(new CraftPermissionListener(this, emptyBallRecipeKey), this);
        BallItemService ballItemService = new BallItemService(keys);
        pm.registerEvents(new BallBlockPlaceListener(this, ballItemService), this);
        pm.registerEvents(new BallDropReleaseListener(this, ballItemService, itemFactory, nbtApiBridge, logService, schedulerFacade), this);
        EntityInfoFormatter formatter = new EntityInfoFormatter();
        String mode = getConfig().getString("interaction-mode", "THROW");
        if (mode.equalsIgnoreCase("THROW")) {
            BallThrower thrower = new BallThrower(this, ballItemService, itemFactory, nbtApiBridge, formatter, logService, schedulerFacade);
            this.packetEventsThrowListener = new PacketEventsThrowListener(this, thrower, ballItemService, schedulerFacade);
            PacketEvents.getAPI().getEventManager().registerListener(packetEventsThrowListener);
        } else {
            pm.registerEvents(new DirectInteractListener(this, ballItemService, itemFactory, nbtApiBridge, formatter, logService), this);
        }
    }

    private void unregisterPacketEventsListener() {
        if (packetEventsThrowListener == null) {
            return;
        }
        PacketEvents.getAPI().getEventManager().unregisterListener(packetEventsThrowListener);
        packetEventsThrowListener = null;
    }

    public boolean isDebugMode(Player player) {
        return player != null && debugPlayers.contains(player.getUniqueId());
    }

    public boolean toggleDebug(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        if (debugPlayers.contains(uuid)) {
            debugPlayers.remove(uuid);
            return false;
        } else {
            debugPlayers.add(uuid);
            return true;
        }
    }

    public void sendDebug(Player player, String message) {
        if (player == null || message == null || !isDebugMode(player)) {
            return;
        }
        if (schedulerFacade != null) {
            schedulerFacade.runOnEntity(player, () -> {
                if (player.isOnline() && isDebugMode(player)) {
                    player.sendMessage(Component.text("[CaptureSpawn] [Debug] " + message, NamedTextColor.GRAY));
                }
            });
            return;
        }
        player.sendMessage(Component.text("[CaptureSpawn] [Debug] " + message, NamedTextColor.GRAY));
    }
}

package cn.oneachina.captureSpawn.protection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

public final class ProtectionHooks {
    private static Plugin plugin;

    private ProtectionHooks() {
    }

    public static void init(Plugin plugin) {
        ProtectionHooks.plugin = plugin;
        registerResidenceCustomFlags();
    }

    public static void refreshResidenceCustomFlags() {
        registerResidenceCustomFlags();
    }

    public static boolean canCapture(Player player, Entity entity, boolean requireBuild) {
        if (player == null || entity == null) {
            return false;
        }
        return canCapture(player, entity.getLocation(), requireBuild);
    }

    public static boolean canCapture(Player player, Location location, boolean requireBuild) {
        if (player == null || location == null) {
            return false;
        }
        Boolean res = residenceAllows(
                player,
                location,
                requireBuild ? "protection.residence.capture-build-flags" : "protection.residence.capture-interact-flags",
                "protection.residence.custom.capture-flag"
        );
        return res != null ? res : !requireResidenceOnly();
    }

    public static boolean canRelease(Player player, Location location, boolean requireBuild) {
        if (player == null || location == null) {
            return false;
        }
        Boolean res = residenceAllows(
                player,
                location,
                requireBuild ? "protection.residence.release-build-flags" : "protection.residence.release-interact-flags",
                "protection.residence.custom.release-flag"
        );
        return res != null ? res : !requireResidenceOnly();
    }

    private static Boolean residenceAllows(Player player, Location loc, String flagsPath, String customFlagPath) {
        if (!isResidenceEnabled() || !isResidenceInstalled()) {
            return requireResidenceOnly() ? Boolean.FALSE : null;
        }
        if (!checkResidenceCustomFlag(player, loc, customFlagPath)) {
            return Boolean.FALSE;
        }
        return checkResidenceFlags(player, loc, flagsPath) ? Boolean.TRUE : Boolean.FALSE;
    }

    private static boolean checkResidenceFlags(Player player, Location loc, String flagsPath) {
        List<String> flags = plugin.getConfig().getStringList(flagsPath);
        if (flags.isEmpty()) {
            return true;
        }
        boolean defaultValue = plugin.getConfig().getBoolean("protection.residence.default-value", true);
        try {
            for (String f : flags) {
                if (f == null || f.isBlank()) {
                    continue;
                }
                com.bekvon.bukkit.residence.containers.Flags flag;
                try {
                    flag = com.bekvon.bukkit.residence.containers.Flags.valueOf(f.trim().toLowerCase(Locale.ROOT));
                } catch (Exception ex) {
                    return false;
                }
                if (!com.bekvon.bukkit.residence.protection.FlagPermissions.has(loc, player, flag, defaultValue)) {
                    return false;
                }
            }
            return true;
        } catch (NoClassDefFoundError | Exception ignored) {
            return false;
        }
    }

    private static boolean checkResidenceCustomFlag(Player player, Location loc, String customFlagPath) {
        if (!plugin.getConfig().getBoolean("protection.residence.custom.enabled", true)) {
            return true;
        }
        String customFlag = plugin.getConfig().getString(customFlagPath, "");
        if (customFlag.isBlank()) {
            return true;
        }
        boolean defaultValue = plugin.getConfig().getBoolean("protection.residence.default-value", true);
        try {
            com.bekvon.bukkit.residence.protection.FlagPermissions perms = com.bekvon.bukkit.residence.protection.FlagPermissions.getPerms(loc, player);
            return perms != null && perms.playerHas(player.getUniqueId(), player.getName(), customFlag.trim(), defaultValue);
        } catch (NoClassDefFoundError | Exception ignored) {
            return true;
        }
    }

    private static void registerResidenceCustomFlags() {
        if (plugin == null || !plugin.getConfig().getBoolean("protection.residence.custom.enabled", true)) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Residence")) {
            return;
        }

        try {
            String capture = plugin.getConfig().getString("protection.residence.custom.capture-flag", "");
            String release = plugin.getConfig().getString("protection.residence.custom.release-flag", "");
            if (!capture.isBlank()) {
                com.bekvon.bukkit.residence.protection.FlagPermissions.addFlag(capture.trim());
            }
            if (!release.isBlank()) {
                com.bekvon.bukkit.residence.protection.FlagPermissions.addFlag(release.trim());
            }
        } catch (NoClassDefFoundError | Exception ignored) {
        }
    }

    private static boolean isResidenceEnabled() {
        return plugin != null && plugin.getConfig().getBoolean("protection.residence.enabled", true);
    }

    private static boolean requireResidenceOnly() {
        return plugin != null && plugin.getConfig().getBoolean("protection.residence.require-installed", false);
    }

    private static boolean isResidenceInstalled() {
        return Bukkit.getPluginManager().getPlugin("Residence") != null;
    }
}

package cn.oneachina.captureSpawn.nbt;

import cn.oneachina.captureSpawn.CaptureSpawn;
import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

public final class NbtApiBridge {
    public NbtApiBridge() {
        if (Bukkit.getPluginManager().getPlugin("NBTAPI") == null) {
            throw new IllegalStateException("NBTAPI plugin is required but not loaded");
        }
    }

    public String saveToSnbt(Entity bukkitEntity, Player caller) {
        try {
            ReadWriteNBT nbt = NBT.createNBTObject();
            NBT.get(bukkitEntity, nbt::mergeCompound);
            String snbt = nbt.toString();

            if (caller != null) {
                CaptureSpawn.instance.sendDebug(caller, "saved entity SNBT: " + snbt);
            }
            return snbt;
        } catch (Exception ex) {
            if (caller != null) {
                CaptureSpawn.instance.sendDebug(caller, "saved SNBT unsuccessfully: " + ex.getMessage());
            }
            return null;
        }
    }

    public boolean loadFromSnbt(Entity bukkitEntity, String snbt, Player caller) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            ReadWriteNBT tag = NBT.parseNBT(snbt);
            sanitize(tag);
            if (caller != null) {
                CaptureSpawn.instance.sendDebug(caller, "loading NBT: " + tag);
            }
            NBT.modify(bukkitEntity, (Consumer<ReadWriteNBT>) nbt -> {
                stripNaturalEquipment(nbt);
                nbt.mergeCompound(tag);
            });
            return false;
        } catch (Exception ex) {
            if (caller != null) {
                CaptureSpawn.instance.sendDebug(caller, "loaded SNBT unsuccessfully: " + ex.getMessage());
            }
            return true;
        }
    }

    private void sanitize(ReadWriteNBT compoundTag) {
        List<String> removeKeys = List.of(
                "UUID",
                "UUIDMost",
                "UUIDLeast",
                "Pos",
                "Motion",
                "Rotation",
                "OnGround",
                "PortalCooldown",
                "Passengers",
                "Leash",
                "LeashUUID",
                "WorldUUIDMost",
                "WorldUUIDLeast",
                "Dimension",
                "id",
                "HandDropChances",
                "ArmorDropChances"
        );
        for (String key : removeKeys) {
            compoundTag.removeKey(key);
        }
    }

    private void stripNaturalEquipment(ReadWriteNBT entityNbt) {
        entityNbt.removeKey("HandItems");
        entityNbt.removeKey("ArmorItems");
        entityNbt.removeKey("HandDropChances");
        entityNbt.removeKey("ArmorDropChances");
    }
}

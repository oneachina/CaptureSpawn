package cn.oneachina.captureSpawn.throwing;

import cn.oneachina.captureSpawn.CaptureSpawn;
import cn.oneachina.captureSpawn.format.EntityInfoFormatter;
import cn.oneachina.captureSpawn.item.BallData;
import cn.oneachina.captureSpawn.item.BallItemService;
import cn.oneachina.captureSpawn.item.ItemFactory;
import cn.oneachina.captureSpawn.logging.BallLogEntry;
import cn.oneachina.captureSpawn.logging.BallLogService;
import cn.oneachina.captureSpawn.nbt.NbtApiBridge;
import cn.oneachina.captureSpawn.nbt.NbtPayloadCodec;
import cn.oneachina.captureSpawn.protection.OwnerUtil;
import cn.oneachina.captureSpawn.protection.ProtectionHooks;
import cn.oneachina.captureSpawn.scheduler.SchedulerFacade;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class BallThrower {
    private final CaptureSpawn plugin;
    private final BallItemService ballItemService;
    private final ItemFactory itemFactory;
    private final NbtApiBridge nbtBridge;
    private final EntityInfoFormatter formatter;
    private final BallLogService logService;
    private final SchedulerFacade scheduler;
    private final Map<UUID, Long> lastTriggerAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    public BallThrower(
            CaptureSpawn plugin,
            BallItemService ballItemService,
            ItemFactory itemFactory,
            NbtApiBridge nbtBridge,
            EntityInfoFormatter formatter,
            BallLogService logService,
            SchedulerFacade scheduler
    ) {
        this.plugin = plugin;
        this.ballItemService = ballItemService;
        this.itemFactory = itemFactory;
        this.nbtBridge = nbtBridge;
        this.formatter = formatter;
        this.logService = logService;
        this.scheduler = scheduler;
    }

    public void throwFromMainHand(Player player, EquipmentSlot hand) {
        if (hand != EquipmentSlot.HAND) {
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        BallData data = ballItemService.read(held);
        if (data == null) {
            return;
        }
        if (!plugin.getConfig().getBoolean("throw.enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastTriggerAt.get(player.getUniqueId());
        if (last != null && now - last < 120L) {
            return;
        }
        lastTriggerAt.put(player.getUniqueId(), now);

        if (data.captured()) {
            if (checkWorldAllowed(player.getWorld(), "release")) {
                send(player, "messages.release.invalid-world", "&c当前世界不允许放出。");
                return;
            }
            if (checkCooldown(player)) {
                send(player, "messages.release.cooldown", "&e冷却中，请稍后再试。");
                return;
            }
            if (plugin.getConfig().getBoolean("release.require-permission", true)
                    && !player.hasPermission("capturespawn.release")) {
                send(player, "messages.release.no-permission", "&c你没有放出权限。");
                return;
            }
        } else {
            if (checkWorldAllowed(player.getWorld(), "capture")) {
                send(player, "messages.capture.invalid-world", "&c当前世界不允许捕捉。");
                return;
            }
            if (checkCooldown(player)) {
                send(player, "messages.capture.cooldown", "&e冷却中，请稍后再试。");
                return;
            }
            if (plugin.getConfig().getBoolean("capture.require-permission", true)
                    && !player.hasPermission("capturespawn.capture")) {
                send(player, "messages.capture.no-permission", "&c你没有捕捉权限。");
                return;
            }
        }

        ItemStack removed = removeOneMainHand(player);
        if (removed == null) {
            return;
        }

        launchProjectile(player, playerRef(player), data, removed);
        markCooldown(player, data.captured());
    }

    private void launchProjectile(Player player, BallLogEntry.PlayerRef actor, BallData data, ItemStack originalBall) {
        Location start = player.getEyeLocation().add(player.getEyeLocation().getDirection().normalize().multiply(0.35));
        ItemDisplay display = spawnDisplay(start, data);

        double speed = plugin.getConfig().getDouble("throw.speed", 1.35);
        final Vector[] velocity = new Vector[]{player.getLocation().getDirection().normalize().multiply(speed)};
        int maxLife = Math.max(20, plugin.getConfig().getInt("throw.max-life-ticks", 80));
        double gravity = plugin.getConfig().getDouble("throw.gravity", 0.045);
        boolean bounceEnabled = plugin.getConfig().getBoolean("throw.bounce.enabled", true) && !data.captured();
        double bounceFactor = plugin.getConfig().getDouble("throw.bounce.factor", 0.62);
        boolean rollEnabled = plugin.getConfig().getBoolean("throw.roll.enabled", true) && !data.captured();
        int rollTicksDefault = Math.max(2, plugin.getConfig().getInt("throw.roll.ticks", 8));
        double rollDamping = plugin.getConfig().getDouble("throw.roll.damping", 0.82);
        double rollInitialScale = plugin.getConfig().getDouble("throw.roll.initial-scale", 0.55);
        double airDrag = clamp(plugin.getConfig().getDouble("throw.physics.air-drag", 0.99), 0.85);
        double restitution = clamp(plugin.getConfig().getDouble("throw.physics.bounce.restitution", bounceFactor), 0.0);
        double surfaceFriction = clamp(plugin.getConfig().getDouble("throw.physics.bounce.friction", 0.2), 0.0);
        int maxBounces = Math.max(0, plugin.getConfig().getInt("throw.physics.bounce.max-bounces", 3));
        double minBounceSpeed = Math.max(0.01, plugin.getConfig().getDouble("throw.physics.bounce.min-speed", 0.25));

        final float[] angle = {0f};
        final int[] lifeTicks = {0};
        final int[] bounceCount = {0};
        final boolean[] rolling = {false};
        final int[] rollTicks = {0};
        scheduler.runEntityTimer(display, 1L, 1L, handle -> {
            lifeTicks[0]++;
            if (!display.isValid() || display.isDead()) {
                handle.cancel();
                return;
            }

            if (lifeTicks[0] >= maxLife) {
                onTimeout(player, actor, originalBall, display.getLocation());
                display.remove();
                handle.cancel();
                return;
            }

            Location current = display.getLocation();
            if (rolling[0]) {
                Vector horizontal = velocity[0].clone();
                horizontal.setY(0);
                if (horizontal.lengthSquared() < 0.0004 || rollTicks[0] <= 0) {
                    onImpactGround(player, actor, data, originalBall, current, null, null);
                    display.remove();
                    handle.cancel();
                    return;
                }
                Location next = current.add(horizontal);
                teleportDisplay(display, snapAboveGround(next));
                horizontal.multiply(rollDamping);
                velocity[0] = horizontal;
                rollTicks[0]--;
                angle[0] += 14.0f;
                Quaternionf left = new Quaternionf()
                        .rotateX((float) Math.toRadians(90.0))
                        .rotateY((float) Math.toRadians(angle[0]));
                display.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        left,
                        new Vector3f(0.75f, 0.75f, 0.75f),
                        new Quaternionf()
                ));
                if (plugin.getConfig().getBoolean("throw.trail.enabled", true)) {
                    display.getWorld().spawnParticle(Particle.CRIT, display.getLocation(), 1, 0.01, 0.01, 0.01, 0.0);
                }
                return;
            }

            Vector stepVec = velocity[0].clone();
            double stepLen = stepVec.length();
            if (stepLen < 0.001) {
                onImpactGround(player, actor, data, originalBall, current, null, null);
                display.remove();
                handle.cancel();
                return;
            }

                RayTraceResult entityHit = current.getWorld().rayTraceEntities(
                        current,
                        stepVec.clone().normalize(),
                        stepLen,
                        0.42,
                        e -> isCollidableTarget(player, e)
                );
                RayTraceResult blockHit = current.getWorld().rayTraceBlocks(
                        current,
                        stepVec.clone().normalize(),
                        stepLen + 0.08,
                        FluidCollisionMode.NEVER,
                        true
                );

                double entDist = hitDistance(current, entityHit);
                double blkDist = hitDistance(current, blockHit);

            if (entDist <= blkDist && entityHit != null && entityHit.getHitEntity() != null) {
                onImpactEntity(player, actor, data, originalBall, entityHit.getHitEntity(), toHitLocation(current, entityHit));
                display.remove();
                handle.cancel();
                return;
            }

            if (blockHit != null && blockHit.getHitBlock() != null) {
                Location hitLoc = toHitLocation(current, blockHit);
                BlockFace face = blockHit.getHitBlockFace();
                if (bounceEnabled && face != null && bounceCount[0] < maxBounces) {
                    Vector normal = face.getDirection().clone();
                    if (normal.lengthSquared() < 0.5) {
                        normal = new Vector(0, 1, 0);
                    }
                    normal.normalize();
                    Vector v = velocity[0].clone();
                    double vnMag = v.dot(normal);
                    Vector vn = normal.clone().multiply(vnMag);
                    Vector vt = v.clone().subtract(vn);
                    vt.multiply(1.0 - surfaceFriction);
                    vn.multiply(-restitution);
                    Vector newVel = vt.add(vn);
                    bounceCount[0]++;
                    velocity[0] = newVel;
                    Location bumped = hitLoc.clone().add(normal.multiply(0.07)).add(0, 0.04, 0);
                    teleportDisplay(display, snapAboveGround(bumped));
                    current.getWorld().playSound(hitLoc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.35f, 1.4f);

                    double remainingRatio = clamp((stepLen - blkDist) / Math.max(0.0001, stepLen), 0.0);
                    if (remainingRatio > 0.01) {
                        Location after = display.getLocation().clone().add(newVel.clone().multiply(remainingRatio));
                        teleportDisplay(display, snapAboveGround(after));
                    }

                    if (newVel.length() < minBounceSpeed) {
                        if (rollEnabled) {
                            Vector tangent = newVel.clone();
                            tangent.setY(0);
                            if (tangent.lengthSquared() > 0.0002) {
                                rolling[0] = true;
                                rollTicks[0] = rollTicksDefault;
                                velocity[0] = tangent.normalize().multiply(rollInitialScale);
                                current.getWorld().playSound(hitLoc, Sound.BLOCK_CALCITE_HIT, 0.35f, 1.2f);
                                return;
                            }
                        }
                        onImpactGround(player, actor, data, originalBall, hitLoc, blockHit.getHitBlock(), face);
                        display.remove();
                        handle.cancel();
                        return;
                    }
                } else {
                    if (rollEnabled) {
                        Vector normal = face == null ? new Vector(0, 1, 0) : face.getDirection();
                        Vector tangent = velocity[0].clone().subtract(normal.multiply(velocity[0].dot(normal)));
                        tangent.setY(0);
                        if (tangent.lengthSquared() > 0.0005) {
                            rolling[0] = true;
                            rollTicks[0] = rollTicksDefault;
                            velocity[0] = tangent.multiply(rollInitialScale);
                            teleportDisplay(display, snapAboveGround(hitLoc.clone().add(0, 0.03, 0)));
                            current.getWorld().playSound(hitLoc, Sound.BLOCK_CALCITE_HIT, 0.35f, 1.2f);
                            return;
                        }
                    }
                    onImpactGround(player, actor, data, originalBall, hitLoc, blockHit.getHitBlock(), face);
                    display.remove();
                    handle.cancel();
                }
                return;
            }

            teleportDisplay(display, current.add(velocity[0]));
            angle[0] += 22.5f;
            Quaternionf left = new Quaternionf()
                    .rotateX((float) Math.toRadians(20.0))
                    .rotateY((float) Math.toRadians(angle[0]));
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    left,
                    new Vector3f(0.75f, 0.75f, 0.75f),
                    new Quaternionf()
            ));

            if (plugin.getConfig().getBoolean("throw.trail.enabled", true)) {
                display.getWorld().spawnParticle(Particle.END_ROD, display.getLocation(), 1, 0.01, 0.01, 0.01, 0);
            }
            velocity[0].multiply(airDrag);
            velocity[0].setY(velocity[0].getY() - gravity);
        });

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.6f, 1.0f);
    }

    private void onImpactEntity(Player player, BallLogEntry.PlayerRef actor, BallData data, ItemStack originalBall, Entity hit, Location hitLoc) {
        if (!(hit instanceof LivingEntity living)) {
            onImpactGround(player, actor, data, originalBall, hitLoc, null, null);
            return;
        }
        if (data.captured()) {
            onImpactGround(player, actor, data, originalBall, hitLoc, null, null);
            return;
        }
        scheduler.runOnEntity(living, () -> {
            String typeName = living.getType().name();
            if (checkWorldAllowed(hitLoc.getWorld(), "capture")) {
                sendThreadSafe(player, "messages.capture.invalid-world", "&c当前世界不允许捕捉。");
                returnBallThreadSafe(player, originalBall, hitLoc);
                logService.log(BallLogEntry.of(actor, "CAPTURE", typeName, hitLoc, "DENIED", "invalid_world"));
                return;
            }
            runPlayerTask(player, () -> {
                if (!isCaptureAllowed(player, hitLoc)) {
                    send(player, "messages.protection.capture", "&c该区域不允许捕捉。");
                    returnBallThreadSafe(player, originalBall, hitLoc);
                    logService.log(BallLogEntry.of(actor, "CAPTURE", typeName, hitLoc, "DENIED", "protection"));
                    return;
                }
                scheduler.runOnEntity(living, () -> startCaptureAnimation(player, actor, originalBall, hitLoc, living));
            });
        });
    }

    private void startCaptureAnimation(Player player, BallLogEntry.PlayerRef actor, ItemStack originalBall, Location hitLoc, LivingEntity living) {
        if (!living.isValid() || living.isDead()) {
            returnBallThreadSafe(player, originalBall, hitLoc);
            return;
        }
        if (!canCaptureType(player, living.getType())) {
            sendThreadSafe(player, "messages.capture.type-blocked", "&c该生物不可捕捉。");
            returnBallThreadSafe(player, originalBall, hitLoc);
            logService.log(BallLogEntry.of(actor, "CAPTURE", living.getType().name(), hitLoc, "FAIL", "type_blocked"));
            return;
        }
        if (!checkOwner(player, living)) {
            returnBallThreadSafe(player, originalBall, hitLoc);
            logService.log(BallLogEntry.of(actor, "CAPTURE", living.getType().name(), hitLoc, "DENIED", "not_owner"));
            return;
        }

        int captureAnimTicks = Math.max(1, plugin.getConfig().getInt("throw.capture-animation-ticks", 6));
        final int[] ticks = {0};
        scheduler.runEntityTimer(living, 0L, 1L, handle -> {
            ticks[0]++;
            if (!living.isValid() || living.isDead()) {
                returnBallThreadSafe(player, originalBall, hitLoc);
                handle.cancel();
                return;
            }
            if (plugin.getConfig().getBoolean("throw.impact.enabled", true)) {
                living.getWorld().spawnParticle(Particle.WITCH, living.getLocation().add(0, 0.8, 0), 8, 0.25, 0.3, 0.25, 0.01);
            }
            if (ticks[0] < captureAnimTicks) {
                return;
            }

            String snbt = nbtBridge.saveToSnbt(living, player);
            if (snbt == null || snbt.isBlank()) {
                returnBallThreadSafe(player, originalBall, hitLoc);
                logService.log(BallLogEntry.of(actor, "CAPTURE", living.getType().name(), hitLoc, "FAIL", "save_nbt_failed"));
                handle.cancel();
                return;
            }
            String format = plugin.getConfig().getString("storage.nbt-format", "GZIP_BASE64");
            String payload = NbtPayloadCodec.encode(snbt, format);
            int maxBytes = Math.max(1024, plugin.getConfig().getInt("storage.max-bytes", 131072));
            int bytes = payload == null ? 0 : payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                returnBallThreadSafe(player, originalBall, hitLoc);
                sendRawThreadSafe(player, plugin.getConfig().getString("messages.capture.too-large", "&c捕捉失败：数据过大。"));
                logService.log(BallLogEntry.of(actor, "CAPTURE", living.getType().name(), hitLoc, "FAIL", "too_large"));
                handle.cancel();
                return;
            }

            BallData filledData = new BallData(true, living.getType().name(), payload);
            ItemStack filledBall = itemFactory.createFilledBall(
                    formatter.displayName(living),
                    formatter.extraLoreLines(living),
                    filledData
            );
            living.remove();
            returnBallThreadSafe(player, filledBall, hitLoc);
            runLocationTask(hitLoc, () -> {
                if (plugin.getConfig().getBoolean("throw.impact.enabled", true)) {
                    hitLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, hitLoc.clone().add(0, 0.5, 0), 12, 0.2, 0.25, 0.2, 0.01);
                }
            });
            logService.log(BallLogEntry.of(actor, "CAPTURE", filledData.entityType(), hitLoc, "SUCCESS", ""));
            handle.cancel();
        });
    }

    private void onImpactGround(Player player, BallLogEntry.PlayerRef actor, BallData data, ItemStack originalBall, Location hitLoc, Block hitBlock, BlockFace hitFace) {
        Location finalLoc = snapAboveGround(hitLoc.clone());
        runLocationTask(finalLoc, () -> {
            if (plugin.getConfig().getBoolean("throw.impact.enabled", true)) {
                finalLoc.getWorld().spawnParticle(Particle.CLOUD, finalLoc, 10, 0.18, 0.06, 0.18, 0.02);
                finalLoc.getWorld().spawnParticle(Particle.CRIT, finalLoc.clone().add(0, 0.1, 0), 16, 0.22, 0.1, 0.22, 0.03);
                finalLoc.getWorld().playSound(finalLoc, Sound.ENTITY_ENDER_PEARL_THROW, 0.65f, 1.25f);
            }
            if (!data.captured()) {
                returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createEmptyBall(), finalLoc);
                return;
            }
            if (checkWorldAllowed(finalLoc.getWorld(), "release")) {
                sendThreadSafe(player, "messages.release.invalid-world", "&c当前世界不允许放出。");
                returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
                logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "DENIED", "invalid_world"));
                return;
            }
            runPlayerTask(player, () -> {
                if (!isReleaseAllowed(player, finalLoc, hitBlock, hitFace)) {
                    send(player, "messages.release.spawn-denied", "&c由于权限原因生成失败（检查领地设置是否开启允许自定义怪物生成）。");
                    returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
                    logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "DENIED", "protection"));
                    return;
                }
                runLocationTask(finalLoc, () -> releaseCapturedEntity(player, actor, data, originalBall, finalLoc));
            });
        });
    }

    private void releaseCapturedEntity(Player player, BallLogEntry.PlayerRef actor, BallData data, ItemStack originalBall, Location finalLoc) {
        if (data.entityType() == null || data.entityNbt() == null) {
            returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
            logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "FAIL", "invalid_data"));
            return;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(data.entityType());
        } catch (Exception ex) {
            returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
            logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "FAIL", "invalid_entity_type"));
            return;
        }

        Entity spawned;
        Location spawnLoc = finalLoc.clone().add(0, 0.22, 0);
        try {
            spawned = finalLoc.getWorld().spawnEntity(spawnLoc, type);
        } catch (Exception ex) {
            returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
            logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "FAIL", "spawn_failed"));
            return;
        }
        if (!spawned.isValid() || spawned.isDead()) {
            sendThreadSafe(player, "messages.release.spawn-denied", "&c由于权限原因生成失败（检查领地设置是否开启允许自定义怪物生成）。");
            returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
            logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "DENIED", "spawn_denied"));
            return;
        }

        String snbt = NbtPayloadCodec.decodeToSnbt(data.entityNbt());
        if (nbtBridge.loadFromSnbt(spawned, snbt, player)) {
            spawned.remove();
            returnBallThreadSafe(player, originalBall != null ? originalBall : itemFactory.createFilledBall(Component.text("未知"), List.of(), data), finalLoc);
            logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "FAIL", "nbt_failed"));
            return;
        }

        boolean consume = plugin.getConfig().getBoolean("release.consume-filled", false);
        if (!consume) {
            returnBallThreadSafe(player, itemFactory.createEmptyBall(), finalLoc);
        }
        logService.log(BallLogEntry.of(actor, "RELEASE", data.entityType(), finalLoc, "SUCCESS", ""));
    }

    private void onTimeout(Player player, BallLogEntry.PlayerRef actor, ItemStack originalBall, Location at) {
        returnBallThreadSafe(player, originalBall, at);
    }

    private ItemDisplay spawnDisplay(Location loc, BallData data) {
        ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class);
        display.setBillboard(Display.Billboard.CENTER);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);

        ItemStack stack = data.captured()
                ? itemFactory.createFilledBall(Component.text(" "), List.of(), data)
                : itemFactory.createEmptyBall();
        stack.setAmount(1);
        display.setItemStack(stack);
        return display;
    }

    private static ItemStack removeOneMainHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            return null;
        }
        ItemStack one = held.clone();
        one.setAmount(1);
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return one;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held);
        return one;
    }

    private void returnBall(Player player, ItemStack item, Location fallbackDrop) {
        if (item == null) {
            return;
        }
        if (player == null || !player.isOnline()) {
            dropAtLocation(fallbackDrop, item);
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            dropAtLocation(fallbackDrop, left);
        }
    }

    private void runPlayerTask(Player player, Runnable task) {
        if (task == null) {
            return;
        }
        if (player == null || !player.isValid()) {
            task.run();
            return;
        }
        scheduler.runOnEntity(player, task);
    }

    private void runLocationTask(Location location, Runnable task) {
        if (task == null) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        scheduler.runAtLocation(location, task);
    }

    private void returnBallThreadSafe(Player player, ItemStack item, Location fallbackDrop) {
        if (player == null || !player.isValid()) {
            runLocationTask(fallbackDrop, () -> returnBall(null, item, fallbackDrop));
            return;
        }
        runPlayerTask(player, () -> returnBall(player, item, fallbackDrop));
    }

    private void sendThreadSafe(Player player, String path, String fallback) {
        runPlayerTask(player, () -> send(player, path, fallback));
    }

    private void sendRawThreadSafe(Player player, String message) {
        runPlayerTask(player, () -> {
            if (player == null || !player.isOnline() || message == null || message.isBlank()) {
                return;
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        });
    }

    private void dropAtLocation(Location location, ItemStack item) {
        if (location == null || location.getWorld() == null || item == null) {
            return;
        }
        runLocationTask(location, () -> location.getWorld().dropItemNaturally(location, item));
    }

    private static boolean isCollidableTarget(Player player, Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (entity.getUniqueId().equals(player.getUniqueId())) {
            return false;
        }
        return entity.isValid() && !entity.isDead();
    }

    private static double hitDistance(Location from, RayTraceResult hit) {
        if (hit == null) {
            return Double.MAX_VALUE;
        } else {
            hit.getHitPosition();
        }
        return hit.getHitPosition().distance(from.toVector());
    }

    private static Location toHitLocation(Location from, RayTraceResult hit) {
        if (hit != null) {
            hit.getHitPosition();
            return hit.getHitPosition().toLocation(from.getWorld());
        }
        return from.clone();
    }

    private static Location snapAboveGround(Location loc) {
        if (loc.getWorld() == null) {
            return loc;
        }
        Location probeStart = loc.clone().add(0, 0.6, 0);
        RayTraceResult down = loc.getWorld().rayTraceBlocks(
                probeStart,
                new Vector(0, -1, 0),
                2.0,
                FluidCollisionMode.NEVER,
                true
        );
        if (down != null) {
            down.getHitPosition();
            return down.getHitPosition().toLocation(loc.getWorld()).add(0, 0.03, 0);
        }
        return loc.clone().add(0, 0.03, 0);
    }

    private void teleportDisplay(ItemDisplay display, Location to) {
        if (display == null || to == null) {
            return;
        }
        if (scheduler.isFolia()) {
            display.teleportAsync(to);
            return;
        }
        display.teleport(to);
    }

    private static double clamp(double v, double min) {
        if (v < min) {
            return min;
        }
        return Math.min(v, 1.0);
    }

    private boolean checkWorldAllowed(World world, String section) {
        if (world == null) {
            return true;
        }
        String enabledPath = section + ".enabled-worlds";
        String disabledPath = section + ".disabled-worlds";
        List<String> enabledList = plugin.getConfig().getStringList(enabledPath);
        List<String> disabledList = plugin.getConfig().getStringList(disabledPath);
        if (section.equalsIgnoreCase("release") && enabledList.isEmpty() && disabledList.isEmpty()) {
            enabledList = plugin.getConfig().getStringList("capture.enabled-worlds");
            disabledList = plugin.getConfig().getStringList("capture.disabled-worlds");
        }
        Set<String> enabled = enabledList.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> disabled = disabledList.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String current = world.getName().toLowerCase(Locale.ROOT);
        if (!enabled.isEmpty() && !enabled.contains(current)) {
            return true;
        }
        return disabled.contains(current);
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(player.getUniqueId());
        return until != null && now < until;
    }

    private void markCooldown(Player player, boolean forRelease) {
        String path = forRelease ? "release.cooldown-ticks" : "capture.cooldown-ticks";
        int ticks = plugin.getConfig().contains(path)
                ? plugin.getConfig().getInt(path, 8)
                : plugin.getConfig().getInt("capture.cooldown-ticks", 8);
        ticks = Math.max(0, ticks);
        if (ticks == 0) {
            cooldownUntil.remove(player.getUniqueId());
            return;
        }
        cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    private boolean canCaptureType(Player player, EntityType type) {
        if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND
                || type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) {
            return false;
        }

        List<String> whitelist = plugin.getConfig().getStringList("capture.whitelist-types");
        if (!whitelist.isEmpty()) {
            Set<String> allow = whitelist.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            return allow.contains(type.name());
        }

        List<String> blacklist = plugin.getConfig().getStringList("capture.blacklist-types");
        if (blacklist.isEmpty()) {
            return true;
        }
        if (player != null && player.hasPermission("capturespawn.bypass.blacklist")) {
            return true;
        }
        Set<String> deny = blacklist.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return !deny.contains(type.name());
    }

    private boolean isCaptureAllowed(Player player, Location location) {
        if (player == null || location == null) {
            return false;
        }
        if (!plugin.getConfig().getBoolean("protection.enabled", true)) {
            return true;
        }
        boolean requireBuild = plugin.getConfig().getBoolean("protection.capture-requires-build", false);
        return ProtectionHooks.canCapture(player, location, requireBuild);
    }

    private boolean checkOwner(Player player, LivingEntity entity) {
        if (!plugin.getConfig().getBoolean("capture.owner-protection.enabled", true)) {
            return true;
        }
        UUID ownerUuid = OwnerUtil.getEntityOwner(entity);
        if (ownerUuid == null) {
            return true;
        }
        if (ownerUuid.equals(player.getUniqueId())) {
            return true;
        }
        if (player.hasPermission("capturespawn.bypass.owner")) {
            return true;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
        String ownerName = owner.getName() != null ? owner.getName() : ownerUuid.toString();
        String template = plugin.getConfig().getString("messages.capture.not-owner", "&c你不能捕捉属于 &e{owner}&c 的驯养生物！");
        String msg = template.replace("{owner}", ownerName);
        sendRawThreadSafe(player, msg);
        return false;
    }

    private boolean isReleaseAllowed(Player player, Location loc, Block hitBlock, BlockFace hitFace) {
        if (player == null || loc == null || loc.getWorld() == null) {
            return false;
        }
        if (!plugin.getConfig().getBoolean("protection.enabled", true)) {
            return true;
        }
        boolean requireBuild = plugin.getConfig().getBoolean("protection.release-requires-build", false);
        Block block = hitBlock != null ? hitBlock : loc.getBlock();
        BlockFace face = hitFace != null ? hitFace : BlockFace.UP;
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
}


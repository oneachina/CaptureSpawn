package cn.oneachina.captureSpawn.throwing;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import cn.oneachina.captureSpawn.CaptureSpawn;
import cn.oneachina.captureSpawn.item.BallItemService;
import cn.oneachina.captureSpawn.scheduler.SchedulerFacade;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class PacketEventsThrowListener extends PacketListenerAbstract {
    private final CaptureSpawn plugin;
    private final BallThrower thrower;
    private final BallItemService ballItemService;
    private final SchedulerFacade scheduler;

    public PacketEventsThrowListener(CaptureSpawn plugin, BallThrower thrower, BallItemService ballItemService, SchedulerFacade scheduler) {
        this.plugin = plugin;
        this.thrower = thrower;
        this.ballItemService = ballItemService;
        this.scheduler = scheduler;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);
            if (packet.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null || !player.isOnline()) {
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (ballItemService.isBall(hand)) {
                event.setCancelled(true);
            }
            scheduler.runOnEntity(player, () -> thrower.throwFromMainHand(player, EquipmentSlot.HAND));
            return;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }
        if (packet.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (ballItemService.isBall(hand)) {
            event.setCancelled(true);
        }
    }
}

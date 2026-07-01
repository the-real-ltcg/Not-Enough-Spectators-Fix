package cheeezer.notenoughspectators;

import cheeezer.notenoughspectators.event.PacketCallback;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;

import java.util.List;

public class NESUtil {
    public static boolean isEquipmentSlot(int slot) {
        return (slot >= 5 && slot <= 8) || (slot >= 36 && slot <= 45) || slot == 0; // /clear command seems to only update slot 0
    }
    public static void updatePlayerEquipment() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, player.getMainHandItem()))));
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.OFFHAND, player.getOffhandItem()))));
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.HEAD, player.getItemBySlot(EquipmentSlot.HEAD)))));
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.CHEST, player.getItemBySlot(EquipmentSlot.CHEST)))));
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.LEGS, player.getItemBySlot(EquipmentSlot.LEGS)))));
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundSetEquipmentPacket(player.getId(), List.of(Pair.of(EquipmentSlot.FEET, player.getItemBySlot(EquipmentSlot.FEET)))));
        }
    }
}

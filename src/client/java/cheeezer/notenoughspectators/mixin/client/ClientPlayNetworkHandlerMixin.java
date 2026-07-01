package cheeezer.notenoughspectators.mixin.client;

import cheeezer.notenoughspectators.NESUtil;
import cheeezer.notenoughspectators.PlayerTaskQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.PositionMoveRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    public void onGameJoin(CallbackInfo ci) {
        PlayerTaskQueue.processTasks(Minecraft.getInstance().player);
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    public void onPlayerPositionLook(CallbackInfo ci) {
        if (Minecraft.getInstance().player == null) return;
        PlayerTaskQueue.processPositionTasks(PositionMoveRotation.of(Minecraft.getInstance().player));
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    public void onInventoryTail(CallbackInfo ci) {
        NESUtil.updatePlayerEquipment();
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    public void onScreenHandlerSlotUpdate(CallbackInfo ci) {
        NESUtil.updatePlayerEquipment();
    }
}

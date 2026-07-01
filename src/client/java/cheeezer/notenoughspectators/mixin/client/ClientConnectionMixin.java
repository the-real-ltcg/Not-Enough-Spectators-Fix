package cheeezer.notenoughspectators.mixin.client;

import cheeezer.notenoughspectators.NESUtil;
import cheeezer.notenoughspectators.PacketSniffer;
import cheeezer.notenoughspectators.event.MovementCallback;
import cheeezer.notenoughspectators.event.PacketCallback;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ClientConnectionMixin {

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    public Channel channel;

    @Shadow private volatile @Nullable PacketListener packetListener;

    @Inject(method = "configureSerialization", at = @At("TAIL"))
    private static void addHandlers(ChannelPipeline pipeline, PacketFlow inboundDirection, boolean local, @Nullable BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (pipeline.get("decompress") instanceof CompressionDecoder) {
            pipeline.addAfter("decompress", "sniffer", new PacketSniffer());
        } else {
            pipeline.addAfter("splitter", "sniffer", new PacketSniffer());
        }
    }


    @Inject(method = "channelRead0", at = @At("HEAD"))
    protected void hookChannelRead(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo ci) {
        if (this.channel.isOpen() && packetListener != null && packetListener.shouldHandleMessage(packet) && receiving == PacketFlow.CLIENTBOUND) {
            ConnectionProtocol phase = PacketSniffer.getNetworkPhase(channelHandlerContext);
            if (phase == ConnectionProtocol.CONFIGURATION) {
                PacketSniffer.addConfigPacket(packet);
            }
        }
    }

    @Inject(method = "channelRead0", at = @At("RETURN"))
    private void hookChannelReadEnd(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (this.channel.isOpen() && packetListener != null && packetListener.shouldHandleMessage(packet) && receiving == PacketFlow.CLIENTBOUND) {
            // Handle packets after they have been handled by the listener (sometimes doesn't work because some handler methods call themselves again on a new thread)
            if (packet instanceof ClientboundLoginPacket gameJoinPacket) {
                PacketSniffer.setSeed(gameJoinPacket.commonPlayerSpawnInfo().seed());
            } else if (packet instanceof ClientboundRespawnPacket respawnPacket) {
                PacketSniffer.setSeed(respawnPacket.commonPlayerSpawnInfo().seed());
            }
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    public void send(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean flush, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (packet instanceof ServerboundMovePlayerPacket) {
            MovementCallback.MovementType movementType = switch (packet) {
                case ServerboundMovePlayerPacket.Pos ignored -> MovementCallback.MovementType.POSITION;
                case ServerboundMovePlayerPacket.Rot ignored -> MovementCallback.MovementType.ROTATION;
                case ServerboundMovePlayerPacket.PosRot ignored -> MovementCallback.MovementType.POSITION_AND_ROTATION;
                default -> MovementCallback.MovementType.UNKNOWN;
            };
            MovementCallback.EVENT.invoker().onMovementPacket(movementType);
        } else if (packet instanceof ServerboundSwingPacket handSwingPacket) {
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundAnimatePacket(player, handSwingPacket.getHand() == InteractionHand.MAIN_HAND ? 0 : 3));
        } else if (packet instanceof ServerboundUseItemPacket useItemPacket) {
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundAnimatePacket(player, useItemPacket.getHand() == InteractionHand.MAIN_HAND ? 0 : 3));
        } else if (packet instanceof ServerboundSetCarriedItemPacket slotUpdatePacket && NESUtil.isEquipmentSlot(slotUpdatePacket.getSlot() + 36) || packet instanceof ServerboundSetCreativeModeSlotPacket invActionPacket && NESUtil.isEquipmentSlot(invActionPacket.slotNum())) {
            NESUtil.updatePlayerEquipment();
        } else if (packet instanceof ServerboundClientCommandPacket statusPacket && statusPacket.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) {
            PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundAddEntityPacket(player, 0, BlockPos.containing(player.position())));
        }
    }

}

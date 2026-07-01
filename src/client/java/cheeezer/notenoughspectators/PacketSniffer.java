package cheeezer.notenoughspectators;

import cheeezer.notenoughspectators.event.PacketCallback;
import cheeezer.notenoughspectators.event.RawPacketCallback;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.core.particles.BlockParticleOption;

import java.util.ArrayList;

public class PacketSniffer extends ChannelInboundHandlerAdapter {
    private static final ArrayList<Packet> CONFIG_PACKETS = new ArrayList<>();
    // PLAY_PACKETS storage moved to PlayPacketCache (chunk-aware, self-releasing, TTL-evicted)
    private static final ArrayList<ByteBuf> QUEUED_PACKETS = new ArrayList<>();
    private static boolean queue = false;
    private static long seed = 0;

    public PacketSniffer() {
        // Clear all packets when joining a new server (release buffers so native memory is freed)
        CONFIG_PACKETS.clear();
        PlayPacketCache.clearAll();
        releaseAndClear(QUEUED_PACKETS);
    }

    private static void releaseAndClear(ArrayList<ByteBuf> list) {
        for (ByteBuf byteBuf : list) {
            byteBuf.release();
        }
        list.clear();
    }

    public static ArrayList<Packet<?>> getConfigPackets() {
        return (ArrayList<Packet<?>>) CONFIG_PACKETS.clone();
    }

    public static ArrayList<ByteBuf> getPlayPackets() {
        // Independent, caller-owned copies in capture order; the caller must release/write them.
        return PlayPacketCache.snapshotForReplay();
    }

    public static void addConfigPacket(Packet<?> packet) {
        CONFIG_PACKETS.add(packet);
    }

    public static void addPlayPacket(ByteBuf byteBuf) {
        PlayPacketCache.storeRaw(byteBuf);
    }

    public static long getSeed() {
        return seed;
    }

    public static void setSeed(long newSeed) {
        seed = newSeed;
    }

    public static synchronized void releaseQueuedPackets() {
        queue = false;
        for (ByteBuf byteBuf : QUEUED_PACKETS) {
            RawPacketCallback.EVENT.invoker().onPacketReceived(byteBuf.copy());
            byteBuf.release();
        }
        QUEUED_PACKETS.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object value) {
        value = HiddenByteBuf.unpack(value);
        if (value instanceof ByteBuf byteBuf && byteBuf.readableBytes() != 0) {
            Packet<?> packet;
            try {
                packet = (Packet<?>) context.pipeline().get(PacketDecoder.class).protocolInfo.codec().decode(byteBuf.copy());
            } catch (Exception e) {
                NotEnoughSpectators.LOGGER.debug("Error decoding packet: " + e.getMessage());
                e.printStackTrace();
                if (e instanceof SkipPacketException) {
                    byteBuf.skipBytes(byteBuf.readableBytes());
                }

                return;
            }
            ConnectionProtocol phase = getNetworkPhase(context);
            if (phase == ConnectionProtocol.PLAY && packet.type().flow() == PacketFlow.CLIENTBOUND) {
                if (!(packet instanceof ClientboundLevelParticlesPacket packet1 && packet1.getParticle() instanceof BlockParticleOption)) { // Certain particles causes a decode error for spectator clients
                    switch (packet) {
                        case ClientboundOpenScreenPacket ignored:
                            break;
                        case ClientboundPlayerPositionPacket ignored:
                            break;
                        case ClientboundContainerSetContentPacket ignored:
                            break;
                        case ClientboundContainerSetSlotPacket ignored:
                            break;
                        case ClientboundContainerSetDataPacket ignored:
                            break;
                        case ClientboundRespawnPacket ignored:
                            break;
                        case ClientboundPlayerAbilitiesPacket ignored:
                            break;
                        case ClientboundPlayerCombatEndPacket ignored:
                            break;
                        case ClientboundSetHealthPacket ignored:
                            break;
                        case ClientboundGameEventPacket gameStateChangePacket:
                            if (gameStateChangePacket.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE) break;
                        default:
                            if (!(packet instanceof ClientboundDamageEventPacket)) {
                                // Don't log entity damage packets, they cause errors when players are joining
                                PlayPacketCache.store(packet, byteBuf.copy());
                            }
                            if (queue) {
                                QUEUED_PACKETS.add(byteBuf.copy());
                            } else {
                                RawPacketCallback.EVENT.invoker().onPacketReceived(byteBuf.copy());
                            }
                            LocalPlayer player = Minecraft.getInstance().player;
                            if (packet instanceof ClientboundLoginPacket) {
                                queue = true;
                            } else if (packet instanceof ClientboundEntityEventPacket entityStatusPacket && player != null && entityStatusPacket.getEntity(player.level()) == player && entityStatusPacket.getEventId() == 3) {
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(1500);
                                    } catch (InterruptedException ignored) {}
                                    if (player != Minecraft.getInstance().player) return; // Player has changed, don't send destroy packet
                                    PacketCallback.EVENT.invoker().onPacketReceived(new ClientboundRemoveEntitiesPacket(player.getId()));
                                }).start();
                            }
                    }
                }
            }
        }

        context.fireChannelRead(value);
    }

    public static ConnectionProtocol getNetworkPhase(ChannelHandlerContext context) {
        PacketDecoder decoderHandler = context.channel().pipeline().get(PacketDecoder.class);

        if (decoderHandler == null) {
            return ConnectionProtocol.LOGIN;
        }
        return decoderHandler.protocolInfo.id();
    }
}

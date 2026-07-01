package cheeezer.notenoughspectators.server;

import cheeezer.notenoughspectators.*;
import cheeezer.notenoughspectators.event.MovementCallback;
import cheeezer.notenoughspectators.event.PacketCallback;
import cheeezer.notenoughspectators.event.RawPacketCallback;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

public class SpectatorServerNetworkHandler extends SimpleChannelInboundHandler<Packet<?>> {
    private static final Logger LOGGER = NotEnoughSpectators.LOGGER;
    SpectatorServer server;
    ConnectionProtocol phase = ConnectionProtocol.HANDSHAKING;
    private Channel channel;
    private StreamCodec codec;
    private boolean duringLogin = false;
    private boolean errored = false;
    private String username;

    public SpectatorServerNetworkHandler(SpectatorServer server) {
        this.server = server;
    }

    // Number of spectators currently connected (shared across all connections).
    private static final AtomicInteger ACTIVE_SPECTATORS = new AtomicInteger();
    // Whether this particular connection counted toward the limit (so it can be uncounted on close).
    private boolean counted = false;

    /** Reset the live spectator count. Called when a new sharing session starts. */
    public static void resetSpectatorCount() {
        ACTIVE_SPECTATORS.set(0);
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        channel = context.channel();
        // Attach embedded ViaVersion translation so spectators on other game versions can join.
        cheeezer.notenoughspectators.via.ViaBridge.inject(context.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Packet<?> packet) throws Exception {
        switch (phase) {
            case ConnectionProtocol.HANDSHAKING:
                if (packet instanceof ClientIntentionPacket handshakePacket) {
                    if (handshakePacket.intention() == ClientIntent.STATUS) {
                        phase = ConnectionProtocol.STATUS;
                        transitionOutbound(StatusProtocols.CLIENTBOUND);
                        transitionInbound(StatusProtocols.SERVERBOUND);
                    } else if (handshakePacket.intention() == ClientIntent.LOGIN) {
                        phase = ConnectionProtocol.LOGIN;
                        login(handshakePacket);
                    } else {
                        context.disconnect();
                    }
                }
                break;
            case ConnectionProtocol.STATUS:
                if (packet instanceof ServerboundStatusRequestPacket) {
                    context.writeAndFlush(new ClientboundStatusResponsePacket(server.getServerMetadata()));
                } else if (packet instanceof ServerboundPingRequestPacket pingPacket) {
                    context.writeAndFlush(new ClientboundPongResponsePacket(pingPacket.getTime()));
                }
                break;
            case ConnectionProtocol.LOGIN:
                if (packet instanceof ServerboundHelloPacket loginPacket) {
                    username = loginPacket.name();
                    context.writeAndFlush(new ClientboundLoginFinishedPacket(UUIDUtil.createOfflineProfile(loginPacket.name()), UUIDUtil.createOfflinePlayerUUID(loginPacket.name())));
                } else if (packet instanceof ServerboundLoginAcknowledgedPacket) {
                    phase = ConnectionProtocol.CONFIGURATION;
                    transitionOutbound(ConfigurationProtocols.CLIENTBOUND);
                    for (Packet packet1 : PacketSniffer.getConfigPackets()) {
                        context.write(packet1);
                    }
                    context.flush();
                    transitionInbound(ConfigurationProtocols.SERVERBOUND);
                    context.channel().pipeline().writeAndFlush(ClientboundFinishConfigurationPacket.INSTANCE);
                }
                break;
            case ConnectionProtocol.CONFIGURATION:
                if (packet instanceof ServerboundFinishConfigurationPacket) {
                    phase = ConnectionProtocol.PLAY;
                    RegistryAccess.Frozen registryManager = Minecraft.getInstance().getConnection().registryAccess();
                    transitionOutbound(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryManager)));

                    codec = context.channel().pipeline().get(PacketEncoder.class).protocolInfo.codec();
                    context.channel().pipeline().remove("encoder");
                    for (ByteBuf byteBuf1 : PacketSniffer.getPlayPackets()) {
                        // getPlayPackets() now returns independent, caller-owned copies.
                        // writeAndFlush takes ownership and releases each one, so nothing leaks here.
                        if (byteBuf1.getByte(0) == 0x2B) {
                            // Modify the packet to give the spectator an entity ID that is not used by any other player
                            // TODO - This is a hacky way to do this, find a better way
                            byteBuf1.setByte(1, Integer.MAX_VALUE);
                        }
                        context.writeAndFlush(byteBuf1);
                    }

                    configureClient();

                    // TODO - Don't busy wait
                    new Thread(() -> {
                        RandomGenerator rand = RandomGenerator.getDefault();
                        while (context.channel().isOpen() && context.channel().isActive()) {
                            try {
                                Thread.sleep(20000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            sendPacket(new ClientboundKeepAlivePacket(rand.nextLong()));
                        }
                    }).start();

                    RawPacketCallback.EVENT.register((buf1) -> {
                        if (!channel.isOpen()) return;
                        ByteBuf buf = ReferenceCountUtil.retain(buf1.copy());
                        if (VarInt.read(buf1.copy()) == 0x2B) {
                            // Modify the packet to give the spectator an entity ID that is not used by any other player
                            // TODO - This is a hacky way to do this, find a better way
                            buf.setByte(1, Integer.MAX_VALUE);

                            PlayerTaskQueue.addTask((player) -> {
                                // Replace codec with new one or else registry keys won't match
                                RegistryAccess.Frozen newRegistryManager = Minecraft.getInstance().getConnection().registryAccess();
                                codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(newRegistryManager)).codec();
                                // Alert the spectator player that they are switching servers
                                sendPacket(new ClientboundDisguisedChatPacket(Component.literal("Switching server..."), ChatType.bind(ChatType.SAY_COMMAND, player.level().registryAccess(), Component.literal("NotEnoughSpectators"))));
                                // Respawn spectator player
                                Level world = player.level();
                                sendPacket(new ClientboundRespawnPacket(new CommonPlayerSpawnInfo(world.dimensionTypeRegistration(), world.dimension(), PacketSniffer.getSeed(), GameType.CREATIVE, null, world.isDebug(), false, Optional.empty(), 0, 0), (byte) 0));
                                // Reconfigure the spectator client
                                configureClient();
                            });

                            // Update spectator position when host receives new position
                            PlayerTaskQueue.addPositionTask((pos) -> {
                                sendPacket(new ClientboundPlayerPositionPacket(Integer.MAX_VALUE, pos, Collections.emptySet()));
                            });
                        }
                        context.channel().writeAndFlush(buf);
                    });
                    PacketCallback.EVENT.register(this::sendPacket);

                    MovementCallback.EVENT.register((movementType) -> {
                        LocalPlayer player = Minecraft.getInstance().player;
                        if (player == null) {
                            return;
                        }
                        switch (movementType) {
                            case MovementCallback.MovementType.POSITION_AND_ROTATION -> {
                                Vec3 delta = player.position().subtract(player.xOld, player.yOld, player.zOld);
                                if (delta.lengthSqr() != 0.0) {
                                    delta = delta.scale(4096.0);
                                    sendPacket(new ClientboundMoveEntityPacket.PosRot(player.getId(), (short) delta.x, (short) delta.y, (short) delta.z, Mth.packDegrees(player.getYRot()), Mth.packDegrees(player.getXRot()), player.onGround()));
                                } else {
                                    sendPacket(new ClientboundEntityPositionSyncPacket(player.getId(), PositionMoveRotation.of(player), player.onGround()));
                                }
                                sendPacket(new ClientboundRotateHeadPacket(player, Mth.packDegrees(player.yHeadRot)));
                            }
                            case MovementCallback.MovementType.POSITION -> {
                                Vec3 delta = player.position().subtract(player.xOld, player.yOld, player.zOld).scale(4096.0);
                                sendPacket(new ClientboundMoveEntityPacket.Pos(player.getId(), (short) delta.x, (short) delta.y, (short) delta.z, player.onGround()));
                            }
                            case MovementCallback.MovementType.ROTATION -> {
                                sendPacket(new ClientboundMoveEntityPacket.Rot(player.getId(), Mth.packDegrees(player.getYRot()), Mth.packDegrees(player.getXRot()), player.onGround()));
                                sendPacket(new ClientboundRotateHeadPacket(player, Mth.packDegrees(player.yHeadRot)));
                            }
                        }
                    });

                    transitionInbound(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryManager), new GameProtocols.Context() {
                        @Override
                        public boolean hasInfiniteMaterials() {
                            return true;
                        }
                    }));

                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player != null && NotEnoughSpectatorsClient.getConfig().shouldAnnounceJoins()) {
                        player.sendSystemMessage(Component.literal(String.format("[%s] %s joined as a spectator!", NotEnoughSpectators.SHORT_NAME, username)));
                    }
                }
                break;
            case ConnectionProtocol.PLAY:
                LocalPlayer player = Minecraft.getInstance().player;
                if (packet instanceof ServerboundChatPacket chatMessagePacket && NotEnoughSpectatorsClient.getConfig().enableSpectatorChat() && player != null) {
                    String message = String.format("<%s> %s", username, chatMessagePacket.message());
                    player.sendSystemMessage(Component.literal(String.format("[%s] %s", NotEnoughSpectators.SHORT_NAME, message)));
                    ClientboundDisguisedChatPacket chatPacket = new ClientboundDisguisedChatPacket(Component.literal(message), ChatType.bind(ChatType.SAY_COMMAND, player.level().registryAccess(), Component.literal(NotEnoughSpectators.SHORT_NAME)));
                    PacketCallback.EVENT.invoker().onPacketReceived(chatPacket);

                    // Also save in packet store so it shows up for new spectators
                    ByteBuf byteBuf = Unpooled.buffer();
                    codec.encode(byteBuf, chatPacket);
                    PacketSniffer.addPlayPacket(byteBuf);
                } else if (packet instanceof ServerboundUseItemPacket && player != null) {
                    // Teleport the spectator to the host player
                    sendPacket(new ClientboundPlayerPositionPacket(Integer.MAX_VALUE, PositionMoveRotation.of(player), Collections.emptySet()));
                }
        }
        context.fireChannelRead(packet);
    }

    private void login(ClientIntentionPacket packet) {
        transitionOutbound(LoginProtocols.CLIENTBOUND);
        if (packet.protocolVersion() != SharedConstants.getCurrentVersion().protocolVersion()) {
            Component text;
            if (packet.protocolVersion() < 754) {
                text = Component.translatable("multiplayer.disconnect.outdated_client", SharedConstants.getCurrentVersion().name());
            } else {
                text = Component.translatable("multiplayer.disconnect.incompatible", SharedConstants.getCurrentVersion().name());
            }

            channel.writeAndFlush(new ClientboundLoginDisconnectPacket(text));
            channel.close().awaitUninterruptibly();
        } else {
            int maxSpectators = NotEnoughSpectatorsClient.getConfig().getMaxSpectators();
            if (maxSpectators > 0 && ACTIVE_SPECTATORS.get() >= maxSpectators) {
                channel.writeAndFlush(new ClientboundLoginDisconnectPacket(Component.literal("Spectator limit reached")));
                channel.close().awaitUninterruptibly();
                return;
            }
            if (!counted) {
                counted = true;
                ACTIVE_SPECTATORS.incrementAndGet();
            }
            transitionInbound(LoginProtocols.SERVERBOUND);
        }
    }

    private void sendPacket(Packet<?> packet) {
        if (channel.isOpen()) {
            if (codec == null) {
                channel.writeAndFlush(packet);
            } else {
                ByteBuf byteBuf = Unpooled.buffer();
                codec.encode(byteBuf, packet);
                channel.writeAndFlush(byteBuf);
            }
        }
    }

    private void configureClient() {
        // Spawn the host player
        LocalPlayer player = Minecraft.getInstance().player;
        sendPacket(new ClientboundAddEntityPacket(player, 0, BlockPos.containing(player.position())));
        sendPacket(new ClientboundEntityPositionSyncPacket(player.getId(), PositionMoveRotation.of(player), true));
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            NESUtil.updatePlayerEquipment();
        }).start();

        // Set spectator attributes
        Abilities playerAbilities = new Abilities();
        playerAbilities.apply(new Abilities.Packed(true, true, true, true, true, 0.05F, 0.1F));
        sendPacket(new ClientboundPlayerAbilitiesPacket(playerAbilities));
        sendPacket(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F));

        // Teleport the spectator to the host player
        sendPacket(new ClientboundPlayerPositionPacket(Integer.MAX_VALUE, PositionMoveRotation.of(player), Collections.emptySet()));

        // Give the spectator a compass
        sendPacket(new ClientboundSetPlayerInventoryPacket(EquipmentSlot.MAINHAND.getIndex(), new ItemStack(Holder.direct(Items.COMPASS), 1, DataComponentPatch.builder().set(DataComponents.ITEM_NAME, Component.literal("Teleport to Host")).build())));

        // Release queued packets
        PacketSniffer.releaseQueuedPackets();
    }

    public void transitionOutbound(ProtocolInfo<?> newState) {
        if (newState.flow() != PacketFlow.CLIENTBOUND) {
            throw new IllegalStateException("Invalid outbound protocol: " + newState.id());
        } else {
            UnconfiguredPipelineHandler.OutboundConfigurationTask task = UnconfiguredPipelineHandler.setupOutboundProtocol(newState);
            BundlerInfo bundlerInfo = newState.bundlerInfo();
            if (bundlerInfo != null) {
                PacketBundleUnpacker packetUnbundler = new PacketBundleUnpacker(bundlerInfo);
                task = task.andThen(context1 -> context1.pipeline().addAfter("encoder", "unbundler", packetUnbundler));
            }

            boolean bl = newState.id() == ConnectionProtocol.LOGIN;
            syncUninterruptibly(channel.writeAndFlush(task.andThen(context1 -> duringLogin = bl)));
        }
    }

    public <T extends PacketListener> void transitionInbound(ProtocolInfo<T> state) {
        if (state.flow() != PacketFlow.SERVERBOUND) {
            throw new IllegalStateException("Invalid inbound protocol: " + state.id());
        } else {
            UnconfiguredPipelineHandler.InboundConfigurationTask task = UnconfiguredPipelineHandler.setupInboundProtocol(state);
            BundlerInfo bundlerInfo = state.bundlerInfo();
            if (bundlerInfo != null) {
                PacketBundlePacker packetBundler = new PacketBundlePacker(bundlerInfo);
                task = task.andThen(context -> context.pipeline().addAfter("decoder", "bundler", packetBundler));
            }

            syncUninterruptibly(channel.writeAndFlush(task));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (counted) {
            counted = false;
            ACTIVE_SPECTATORS.decrementAndGet();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable ex) {
        if (ex instanceof SkipPacketException) {
            LOGGER.debug("Skipping buf due to errors", ex.getCause());
        } else {
            boolean bl = !errored;
            errored = true;
            if (channel.isOpen()) {
                if (ex instanceof TimeoutException) {
                    LOGGER.debug("Timeout", ex);
                    disconnect(new DisconnectionDetails(Component.translatable("disconnect.timeout")));
                } else {
                    Component text = Component.translatable("disconnect.genericReason", "Internal Exception: " + ex);
                    DisconnectionDetails disconnectionInfo = new DisconnectionDetails(text);

                    if (bl) {
                        LOGGER.debug("Failed to sent buf", ex);
                        Packet<?> buf = duringLogin ? new ClientboundLoginDisconnectPacket(text) : new ClientboundDisconnectPacket(text);
                        sendPacket(buf);
                        disconnect(disconnectionInfo);

                        if (channel != null) {
                            channel.config().setAutoRead(false);
                        }
                    } else {
                        LOGGER.debug("Double fault", ex);
                        disconnect(disconnectionInfo);
                    }
                }
            }
        }
    }

    private void disconnect(DisconnectionDetails disconnectionInfo) {
        LOGGER.debug("Disconnecting due to: {}", disconnectionInfo.reason());
        if (channel.isOpen()) {
            channel.close().awaitUninterruptibly();
        } else {
            LOGGER.debug("Channel already closed, not sending disconnect packet");
        }
    }

    private static void syncUninterruptibly(ChannelFuture future) {
        try {
            future.syncUninterruptibly();
        } catch (Exception e) {
            if (e instanceof ClosedChannelException) {
                LOGGER.info("Connection closed during protocol change");
            } else {
                throw e;
            }
        }
    }
}

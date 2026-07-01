package cheeezer.notenoughspectators;

import cheeezer.notenoughspectators.config.NESConfigData;
import cheeezer.notenoughspectators.server.SpectatorServer;
import cheeezer.notenoughspectators.tunnel.TunnelClient;
import cheeezer.notenoughspectators.via.ViaBridge;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

public class NotEnoughSpectatorsClient implements ClientModInitializer {
    private static NESConfigData config;
    private SpectatorServer server;
    private TunnelClient tunnelClient;

    @Override
    public void onInitializeClient() {
        AutoConfig.register(NESConfigData.class, Toml4jConfigSerializer::new);
        config = AutoConfig.getConfigHolder(NESConfigData.class).getConfig();

        // Boot embedded ViaVersion so spectators on other game versions can connect.
        ViaBridge.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            final LiteralCommandNode<FabricClientCommandSource> nesNode = dispatcher.register(literal("notenoughspectators").then(literal("share").executes(this::runShareCommand).then(argument("localPort", IntegerArgumentType.integer(1, 65535)).executes(this::runShareCommand))).then(literal("stop").executes(context -> {
                if (server == null) {
                    context.getSource().sendError(Component.literal("No server is currently running!"));
                    return 0;
                }
                stopServer();
                stopTunnelClient();
                context.getSource().sendFeedback(Component.literal("Server stopped!"));
                return 1;
            })).then(literal("limit").executes(this::runLimitCommand).then(argument("count", IntegerArgumentType.integer(0)).executes(this::runLimitCommand))).then(literal("via").executes(this::runViaCommand)));
            dispatcher.register(literal("nes").redirect(nesNode));
        });

        // Auto-evict cached chunk packets older than 3 minutes (fixes the unbounded PLAY_PACKETS leak).
        // sweepOldChunks self-throttles to one real scan per second, so calling it every tick is cheap.
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                PlayPacketCache.sweepOldChunks(PlayPacketCache.CHUNK_TTL_MS));
    }

    public static NESConfigData getConfig() {
        return config;
    }

    // Typed brigadier builder factories pinned to FabricClientCommandSource so that the deeply
    // nested command tree below infers correctly (the raw static brigadier helpers default S to Object).
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private int runShareCommand(CommandContext<FabricClientCommandSource> context) {
        final FabricClientCommandSource source = context.getSource();
        new Thread(() -> {
            if (server != null) {
                feedback(source, true, Component.literal("A server is already running!"));
                return;
            }
            try {
                int localPort;
                try {
                    localPort = IntegerArgumentType.getInteger(context, "localPort");
                } catch (IllegalArgumentException ignored) {
                    localPort = config.getLocalPort(); // Default port if not specified
                }
                startServer(localPort);
                String address;
                if (config.shouldTunnel()) {
                    int port = startTunnelClient(localPort);
                    address = String.format("%s:%d", NotEnoughSpectatorsClient.getConfig().getBoreServerHost(), port);
                } else {
                    address = String.format("localhost:%d", localPort);
                }

                final String addr = address;
                feedback(source, false, Component.literal("Server started! Join at ").append(Component.literal(addr).setStyle(Style.EMPTY.withUnderlined(true).withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))).withClickEvent(new ClickEvent.CopyToClipboard(addr)))));
            } catch (Exception e) {
                feedback(source, true, Component.literal("Failed to start server: " + e.getMessage()));
            }
        }).start();
        return 1;
    }

    /**
     * Send command feedback/errors on the client main thread. The share command runs its network
     * setup on a background thread (so it doesn't freeze the client), but Minecraft 26.2 asserts
     * that chat/HUD calls happen on the render thread, so the message itself must be marshalled back.
     */
    private static void feedback(FabricClientCommandSource source, boolean error, Component message) {
        Minecraft.getInstance().execute(() -> {
            if (error) {
                source.sendError(message);
            } else {
                source.sendFeedback(message);
            }
        });
    }

    private int runViaCommand(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (!ViaBridge.isEnabled()) {
            source.sendError(Component.literal("[NES] Cross-version support is OFF (ViaVersion failed to start). Spectators must use 26.2."));
            return 0;
        }
        source.sendFeedback(Component.literal("[NES] Cross-version support ON — host protocol "
                + ProtocolVersion.v26_2.getName() + "; spectators may join from other versions."));
        var connections = ViaBridge.connections();
        if (connections.isEmpty()) {
            source.sendFeedback(Component.literal("No spectator connections are being tracked right now."));
            return 1;
        }
        source.sendFeedback(Component.literal(connections.size() + " spectator connection(s):"));
        for (UserConnection user : connections) {
            ProtocolInfo info = user.getProtocolInfo();
            String name = info != null && info.getUsername() != null ? info.getUsername() : "<connecting>";
            ProtocolVersion version = info != null ? info.protocolVersion() : null;
            String versionName = version != null ? version.getName() : "negotiating";
            source.sendFeedback(Component.literal(" - " + name + ": " + versionName));
        }
        return 1;
    }

    private int runLimitCommand(CommandContext<FabricClientCommandSource> context) {
        Integer count = null;
        try {
            count = IntegerArgumentType.getInteger(context, "count");
        } catch (IllegalArgumentException ignored) {
        }
        if (count == null) {
            int current = config.getMaxSpectators();
            context.getSource().sendFeedback(Component.literal("Spectator limit: " + (current == 0 ? "unlimited" : current)));
        } else {
            config.setMaxSpectators(count);
            AutoConfig.getConfigHolder(NESConfigData.class).save();
            context.getSource().sendFeedback(Component.literal("Spectator limit set to " + (count == 0 ? "unlimited" : count)));
        }
        return 1;
    }

    private void startServer(int port) throws Exception {
        if (server == null) {
            SpectatorServer newServer = new SpectatorServer(port);
            newServer.setup();
            newServer.setDaemon(true);
            newServer.start();
            server = newServer;
        }
    }

    private void stopServer() {
        if (server != null) {
            server.interrupt();
            server = null;
        }
    }

    private int startTunnelClient(int port) throws Exception {
        if (tunnelClient == null) {
            tunnelClient = new TunnelClient(port);
            tunnelClient.setDaemon(true);
            tunnelClient.start();
            return tunnelClient.getRemotePort();
        }
        return 0;
    }

    private void stopTunnelClient() {
        if (tunnelClient != null) {
            tunnelClient.interrupt();
            tunnelClient = null;
        }
    }
}
package cheeezer.notenoughspectators.via;

import cheeezer.notenoughspectators.NotEnoughSpectators;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.platform.ViaChannelInitializer;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded ViaVersion cross-version support for Not Enough Spectators.
 *
 * <p>NES runs a small backend Netty server and replays the host's captured 26.2 packets to
 * spectators. {@link #init()} boots a minimal Via platform whose server version is 26.2 (plus
 * ViaBackwards + ViaRewind for the full range of older clients), and {@link #inject(Channel)}
 * splices server-side Via translation handlers into each spectator channel — exactly how a backend
 * server with ViaVersion installed works.</p>
 *
 * <p>Everything is best-effort: if Via fails to start, {@link #isEnabled()} stays {@code false} and
 * {@link #inject(Channel)} is a no-op, so NES keeps working normally for same-version spectators.</p>
 */
public final class ViaBridge {

    private static volatile boolean enabled = false;
    private static final Set<UserConnection> ACTIVE = ConcurrentHashMap.newKeySet();

    private ViaBridge() {
    }

    /** Boots the ViaVersion stack once, at client startup. Safe to call more than once. */
    public static synchronized void init() {
        if (enabled) {
            return;
        }
        try {
            File dataFolder = FabricLoader.getInstance().getConfigDir().resolve("not-enough-spectators-via").toFile();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                NotEnoughSpectators.LOGGER.warn("[NES] Could not create Via data folder {}", dataFolder);
            }

            BridgePlatform platform = new BridgePlatform(dataFolder);
            ViaManagerImpl manager = ViaManagerImpl.builder()
                    .platform(platform)
                    .injector(new BridgeInjector())
                    .loader(new BridgeLoader())
                    .build();
            Via.init(manager);
            manager.init();
            manager.onServerLoaded();
            enabled = true;
            NotEnoughSpectators.LOGGER.info("[NES] ViaVersion cross-version support enabled (host protocol 26.2).");
        } catch (Throwable t) {
            enabled = false;
            NotEnoughSpectators.LOGGER.error("[NES] Failed to enable ViaVersion cross-version support; spectators must use 26.2.", t);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Splices Via's server-side translation handlers into a freshly-active spectator channel.
     * No-op if Via is disabled or already attached.
     */
    public static void inject(Channel channel) {
        if (!enabled || channel == null) {
            return;
        }
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(ViaDecodeHandler.NAME) != null) {
                return; // already attached
            }
            // serverSide = false -> backend connection translating the spectator's version to/from 26.2.
            UserConnection user = ViaChannelInitializer.createUserConnection(channel, false);
            String encoderName = pipeline.get("outbound_config") != null ? "outbound_config" : "encoder";
            pipeline.addBefore(encoderName, ViaEncodeHandler.NAME, new ViaEncodeHandler(user));
            pipeline.addBefore("decoder", ViaDecodeHandler.NAME, new ViaDecodeHandler(user));

            ACTIVE.add(user);
            channel.closeFuture().addListener(future -> ACTIVE.remove(user));
        } catch (Throwable t) {
            NotEnoughSpectators.LOGGER.warn("[NES] Failed to attach Via translation to a spectator channel", t);
        }
    }

    /** Live set of spectator connections currently being translated (for {@code /nes via}). */
    public static Set<UserConnection> connections() {
        return ACTIVE;
    }
}

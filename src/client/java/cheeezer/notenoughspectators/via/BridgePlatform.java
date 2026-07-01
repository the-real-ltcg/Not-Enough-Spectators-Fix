package cheeezer.notenoughspectators.via;

import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.configuration.AbstractViaConfig;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The {@link ViaPlatform} implementation for the NES spectator server. It is a "backend server"
 * style platform: a single native version (26.2) that many client versions translate to/from.
 */
public class BridgePlatform implements ViaPlatform<UUID> {

    private final Logger logger = Logger.getLogger("NotEnoughSpectators-Via");
    private final File dataFolder;
    private final ViaAPI<UUID> api = new BridgeApi();
    private final ViaVersionConfig conf;

    public BridgePlatform(File dataFolder) {
        this.dataFolder = dataFolder;
        AbstractViaConfig config = new AbstractViaConfig(new File(dataFolder, "viaversion.yml"), logger) {
        };
        try {
            config.reload();
        } catch (Throwable t) {
            logger.warning("Could not load ViaVersion config, continuing with defaults: " + t.getMessage());
        }
        this.conf = config;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public String getPlatformName() {
        return "NotEnoughSpectators";
    }

    @Override
    public String getPlatformVersion() {
        return "1.2.0";
    }

    @Override
    public ViaAPI<UUID> getApi() {
        return api;
    }

    @Override
    public ViaVersionConfig getConf() {
        return conf;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }
}

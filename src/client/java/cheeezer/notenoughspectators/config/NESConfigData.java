package cheeezer.notenoughspectators.config;

import cheeezer.notenoughspectators.NotEnoughSpectators;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = NotEnoughSpectators.MOD_ID)
public class NESConfigData implements ConfigData {
    boolean shouldAnnounceJoins = true;
    boolean enableSpectatorChat = true;
    int localPort = 25566;
    boolean shouldTunnel = true;
    String boreServerHost = "bore.pub";
    @ConfigEntry.Gui.Tooltip
    int maxSpectators = 0;

    public boolean shouldAnnounceJoins() {
        return shouldAnnounceJoins;
    }

    public boolean enableSpectatorChat() {
        return enableSpectatorChat;
    }

    public int getLocalPort() {
        return localPort;
    }

    public boolean shouldTunnel() {
        return shouldTunnel;
    }

    public String getBoreServerHost() {
        return boreServerHost;
    }

    public int getMaxSpectators() {
        return maxSpectators;
    }

    public void setMaxSpectators(int maxSpectators) {
        this.maxSpectators = maxSpectators;
    }
}

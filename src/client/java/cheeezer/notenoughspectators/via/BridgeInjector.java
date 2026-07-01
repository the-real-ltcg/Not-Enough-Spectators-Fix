package cheeezer.notenoughspectators.via;

import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.gson.JsonObject;

/**
 * We inject the Via handlers ourselves (per spectator connection), so {@link #inject()} /
 * {@link #uninject()} are no-ops. The important part is {@link #getServerProtocolVersion()}: it
 * tells Via the native version the NES host speaks (26.2), the target every spectator's protocol
 * is translated to/from.
 */
public class BridgeInjector implements ViaInjector {

    @Override
    public void inject() {
    }

    @Override
    public void uninject() {
    }

    @Override
    public ProtocolVersion getServerProtocolVersion() {
        return ProtocolVersion.v26_2;
    }

    @Override
    public JsonObject getDump() {
        return new JsonObject();
    }
}

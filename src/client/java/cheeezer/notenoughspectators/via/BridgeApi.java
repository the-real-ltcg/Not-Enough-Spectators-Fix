package cheeezer.notenoughspectators.via;

import com.viaversion.viaversion.ViaAPIBase;

import java.util.UUID;

/**
 * Minimal {@link com.viaversion.viaversion.api.ViaAPI} implementation. {@link ViaAPIBase}
 * already implements everything we need for a UUID-keyed, backend-style platform.
 */
public class BridgeApi extends ViaAPIBase<UUID> {

    // With T = UUID, ViaAPI#getPlayerVersion(T) and #getPlayerVersion(UUID) collapse to the same
    // erasure and Java sees two unrelated default methods, so we must override to disambiguate.
    @Override
    public int getPlayerVersion(UUID uuid) {
        return getPlayerProtocolVersion(uuid).getVersion();
    }
}

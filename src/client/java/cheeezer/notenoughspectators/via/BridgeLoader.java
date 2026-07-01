package cheeezer.notenoughspectators.via;

import com.viaversion.viabackwards.ViaBackwardsPlatformImpl;
import com.viaversion.viarewind.ViaRewindPlatformImpl;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;

/**
 * Loads the sub-platforms. ViaBackwards (modern -> old) and ViaRewind (1.8/1.7-era) both
 * self-register their protocols in their constructors, giving us the full version range.
 */
public class BridgeLoader implements ViaPlatformLoader {

    @Override
    public void load() {
        new ViaBackwardsPlatformImpl();
        new ViaRewindPlatformImpl();
    }

    @Override
    public void unload() {
    }
}

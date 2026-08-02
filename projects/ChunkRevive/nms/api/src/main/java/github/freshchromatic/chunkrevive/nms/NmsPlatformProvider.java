package github.freshchromatic.chunkrevive.nms;

import java.util.Set;

/** Lightweight service provider; it must not initialize version-specific NMS classes. */
public interface NmsPlatformProvider {
    Set<String> supportedMinecraftVersions();

    NmsPlatform create();
}

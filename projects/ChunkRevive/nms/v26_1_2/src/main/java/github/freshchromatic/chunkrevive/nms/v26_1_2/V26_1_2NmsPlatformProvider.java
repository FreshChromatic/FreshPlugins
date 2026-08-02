package github.freshchromatic.chunkrevive.nms.v26_1_2;

import github.freshchromatic.chunkrevive.nms.NmsPlatform;
import github.freshchromatic.chunkrevive.nms.NmsPlatformProvider;

import java.util.Set;

/** Contains no NMS fields, so ServiceLoader can inspect it safely on every server version. */
public final class V26_1_2NmsPlatformProvider implements NmsPlatformProvider {
    @Override
    public Set<String> supportedMinecraftVersions() {
        return Set.of("26.1.2");
    }

    @Override
    public NmsPlatform create() {
        return new V26_1_2NmsPlatform();
    }
}

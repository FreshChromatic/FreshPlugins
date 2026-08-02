package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Version-independent port used by core features to query external land claims. */
public interface LandProtection {
    boolean isEnabled();

    boolean hasClaim(World world, int chunkX, int chunkZ);

    @Nullable
    List<int[]> resolveEffectiveChunks(
        String world,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        PluginConfig.Structure.PartialClaimPolicy policy
    );
}

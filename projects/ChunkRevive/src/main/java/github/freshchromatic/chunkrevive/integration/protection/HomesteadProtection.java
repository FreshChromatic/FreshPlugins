package github.freshchromatic.chunkrevive.integration.protection;

import me.tayebyassine.homestead.managers.ChunkManager;
import org.bukkit.World;

final class HomesteadProtection extends ExternalProtection {
    HomesteadProtection() {
        super("Homestead");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return ChunkManager.findChunk(world.getUID(), x, z) != null;
    }
}

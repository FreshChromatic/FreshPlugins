package github.freshchromatic.chunkrevive.integration.protection;

import org.ayosynk.landClaimPlugin.api.LandClaimAPI;
import org.bukkit.World;

final class LandClaimProtection extends ExternalProtection {
    LandClaimProtection() {
        super("LandClaimPlugin");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return LandClaimAPI.getInstance().isChunkClaimed(world.getName(), x, z);
    }
}

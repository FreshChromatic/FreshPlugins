package github.freshchromatic.chunkrevive.integration.protection;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.World;

final class GriefPreventionProtection extends ExternalProtection {
    GriefPreventionProtection() {
        super("GriefPrevention");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return GriefPrevention.instance.dataStore.getClaims(x, z).stream()
            .anyMatch(claim -> claim.getLesserBoundaryCorner().getWorld().equals(world));
    }
}

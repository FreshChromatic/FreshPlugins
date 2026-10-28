package github.freshchromatic.chunkrevive.integration.protection;

import com.griefdefender.api.GriefDefender;
import org.bukkit.World;

final class GriefDefenderProtection extends ExternalProtection {
    GriefDefenderProtection() {
        super("GriefDefender");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return GriefDefender.getCore().getAllClaims().stream()
            .filter(claim -> world.getUID().equals(claim.getWorldUniqueId()) && !claim.isWilderness())
            .anyMatch(claim -> claim.getChunkPositions().stream().anyMatch(pos -> pos.getX() == x && pos.getZ() == z));
    }
}

package github.freshchromatic.chunkrevive.integration.protection;

import com.palmergames.bukkit.towny.TownyAPI;
import org.bukkit.Location;
import org.bukkit.World;

final class TownyProtection extends ExternalProtection {
    TownyProtection() {
        super("Towny");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                if (!TownyAPI.getInstance().isWilderness(new Location(world, (x << 4) + dx, 0, (z << 4) + dz))) return true;
            }
        }
        return false;
    }
}

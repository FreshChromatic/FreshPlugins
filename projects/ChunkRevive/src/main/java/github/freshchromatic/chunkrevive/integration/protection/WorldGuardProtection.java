package github.freshchromatic.chunkrevive.integration.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.World;

final class WorldGuardProtection extends ExternalProtection {
    WorldGuardProtection() {
        super("WorldGuard");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        var manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) return false;
        int minX = x << 4;
        int minZ = z << 4;
        var chunk = new ProtectedCuboidRegion("__chunkrevive_chunk__", true,
            BlockVector3.at(minX, world.getMinHeight(), minZ), BlockVector3.at(minX + 15, world.getMaxHeight() - 1, minZ + 15));
        return manager.getApplicableRegions(chunk).size() > 0;
    }
}

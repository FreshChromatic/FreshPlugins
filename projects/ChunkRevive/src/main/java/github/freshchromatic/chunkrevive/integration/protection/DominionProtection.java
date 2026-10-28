package github.freshchromatic.chunkrevive.integration.protection;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import org.bukkit.World;

final class DominionProtection extends ExternalProtection {
    DominionProtection() {
        super("Dominion");
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return CacheManager.instance.getCache().getDominionCache().getDominionsByWorld(world).stream()
            .anyMatch(dominion -> intersects(dominion.getCuboid(), x, z));
    }

    static boolean intersects(CuboidDTO cuboid, int chunkX, int chunkZ) {
        long minX = (long) chunkX * 16;
        long minZ = (long) chunkZ * 16;
        return cuboid.x1() < minX + 16 && cuboid.x2() > minX
            && cuboid.z1() < minZ + 16 && cuboid.z2() > minZ;
    }
}

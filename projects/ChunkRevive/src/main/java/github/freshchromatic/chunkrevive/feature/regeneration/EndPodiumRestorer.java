package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.DragonBattle;
import org.bukkit.plugin.Plugin;

import java.util.Collection;

/**
 * Restores the End exit podium after regeneration touches its four-chunk footprint.
 *
 * <p>The podium is not a placed biome feature: vanilla's dragon-fight controller creates it
 * separately. A chunk-only regeneration therefore needs this small world-state repair after
 * its terrain has been written and applied.</p>
 */
final class EndPodiumRestorer {
    private static final int MIN_PODIUM_CHUNK = -1;
    private static final int MAX_PODIUM_CHUNK = 0;

    private final Plugin plugin;

    EndPodiumRestorer(Plugin plugin) {
        this.plugin = plugin;
    }

    void restoreIfAffected(World world, Collection<MarkedChunk> chunks) {
        if (world.getEnvironment() != World.Environment.THE_END || !touchesPodium(chunks)) return;

        // DragonBattle and world blocks must be accessed on the End's owning region thread.
        Scheduler.runTask(plugin, () -> restore(world), new Location(world, 0, 64, 0));
    }

    static boolean touchesPodium(Collection<MarkedChunk> chunks) {
        return chunks.stream().anyMatch(chunk -> chunk.cx() >= MIN_PODIUM_CHUNK
            && chunk.cx() <= MAX_PODIUM_CHUNK
            && chunk.cz() >= MIN_PODIUM_CHUNK
            && chunk.cz() <= MAX_PODIUM_CHUNK);
    }

    private static void restore(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) return;

        boolean active = battle.hasBeenPreviouslyKilled();
        if (battle.generateEndPortal(active)) return;

        // Paper deliberately refuses generateEndPortal once the fight has a cached portal location,
        // even if regeneration just removed its blocks. Reapply the same EndPodiumFeature layout at
        // that authoritative location instead of resetting any dragon-fight saved state.
        Location origin = battle.getEndPortalLocation();
        if (origin != null) placePodium(world, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), active);
    }

    private static void placePodium(World world, int originX, int originY, int originZ, boolean active) {
        for (int x = originX - 4; x <= originX + 4; x++) {
            for (int y = originY - 1; y <= originY + 32; y++) {
                for (int z = originZ - 4; z <= originZ + 4; z++) {
                    double distanceSquared = squaredDistance(x, z, originX, originZ);
                    boolean insidePortal = distanceSquared < 2.5 * 2.5;
                    if (!insidePortal && distanceSquared >= 3.5 * 3.5) continue;

                    Block block = world.getBlockAt(x, y, z);
                    if (y < originY) {
                        set(block, insidePortal ? Material.BEDROCK : Material.END_STONE);
                    } else if (y > originY) {
                        set(block, Material.AIR);
                    } else if (!insidePortal) {
                        set(block, Material.BEDROCK);
                    } else {
                        set(block, active ? Material.END_PORTAL : Material.AIR);
                    }
                }
            }
        }

        for (int y = 0; y < 4; y++) set(world.getBlockAt(originX, originY + y, originZ), Material.BEDROCK);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block torch = world.getBlockAt(originX + face.getModX(), originY + 2, originZ + face.getModZ());
            torch.setType(Material.WALL_TORCH, false);
            Directional data = (Directional) torch.getBlockData();
            data.setFacing(face);
            torch.setBlockData(data, false);
        }
    }

    private static double squaredDistance(int x, int z, int originX, int originZ) {
        int dx = x - originX;
        int dz = z - originZ;
        return dx * dx + dz * dz;
    }

    private static void set(Block block, Material material) {
        if (block.getType() != material) block.setType(material, false);
    }
}

package github.freshchromatic.chunkrevive.nms;

import org.bukkit.World;

import java.util.Set;

/** Version-specific biome registry, worldgen sampling and read-only Anvil access. */
public interface WorldScanGateway {
    BiomeSampler biomeSampler(World world, HeightmapKind heightmap);

    Set<String> biomeIds(World world);

    DiskChunkSession openDiskSession(World world);
}

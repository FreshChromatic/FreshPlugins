package github.freshchromatic.chunkrevive.nms;

import org.bukkit.World;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Version-specific online chunk-holder and region-storage operations. */
public interface ChunkStorageGateway {
    int generationPadding();

    Optional<ChunkCoordinate> firstHolder(World world, ChunkArea area);

    default CompletableFuture<Long> deleteChunk(World world, int chunkX, int chunkZ) {
        return deleteChunks(world, List.of(new ChunkCoordinate(chunkX, chunkZ)));
    }

    /** Deletes chunks in region-I/O batches. Implementations must safely split cross-region input. */
    CompletableFuture<Long> deleteChunks(World world, Collection<ChunkCoordinate> chunks);

    CompletableFuture<Long> pruneRegion(World world, int regionX, int regionZ, boolean forceToDisk);

    /**
     * Scans terrain, POI and entity region files for files whose location table is empty but whose
     * physical length still exceeds the mandatory 8 KiB Anvil header.
     */
    CompletableFuture<List<EmptyRegionInfo>> scanEmptyRegions(World world);

    /**
     * Truncates only storage files that are still empty when their Moonrise region-I/O task runs.
     * Implementations must not clear any chunk entry as part of this operation.
     */
    CompletableFuture<Long> pruneEmptyRegion(World world, int regionX, int regionZ, boolean forceToDisk);
}

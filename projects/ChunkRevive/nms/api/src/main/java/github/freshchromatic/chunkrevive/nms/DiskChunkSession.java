package github.freshchromatic.chunkrevive.nms;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Read-only access to one world's Anvil chunk storage. */
public interface DiskChunkSession extends AutoCloseable {
    Path regionFolder();

    boolean exists(int chunkX, int chunkZ, ChunkStage minimumStage);

    RegionScanResult scanRegion(
        int regionX,
        int regionZ,
        ChunkStage minimumStage,
        Predicate<String> structureFilter,
        BooleanSupplier cancelled
    ) throws IOException;

    @Override
    void close();
}

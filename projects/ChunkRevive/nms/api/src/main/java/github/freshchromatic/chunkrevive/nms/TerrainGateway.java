package github.freshchromatic.chunkrevive.nms;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** All terrain-generation operations whose implementation is tied to a server version. */
public interface TerrainGateway {
    void initialize(JavaPlugin plugin);

    void configure(TerrainSettings settings);

    CompletableFuture<Void> generate(
        World world,
        Collection<ChunkCoordinate> chunks,
        long seed,
        IntConsumer progress,
        Consumer<String> stage,
        BooleanSupplier cancelled,
        int[] extraContextBounds
    );

    void beginBulkSession(int targetCount);

    void endBulkSession();

    int activeGenerationThreads();

    int resolvedParallelism();

    void shutdown();
}

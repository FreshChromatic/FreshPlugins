package github.freshchromatic.chunkrevive.nms.v26_1_2.terrain;

import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.TerrainGateway;
import github.freshchromatic.chunkrevive.nms.TerrainSettings;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class V26_1_2TerrainGateway implements TerrainGateway {
    @Override
    public void initialize(JavaPlugin plugin) {
        V26_1_2TerrainEngine.init(plugin);
    }

    @Override
    public void configure(TerrainSettings settings) {
        V26_1_2TerrainEngine.setEntityExemptions(settings.entityExemptions());
        V26_1_2TerrainEngine.setThreadPoolConfig(settings.threadPool());
        V26_1_2TerrainEngine.setMemorySafetyConfig(settings.memorySafety());
        V26_1_2TerrainEngine.setContextRadius(settings.contextRadius());
        V26_1_2TerrainEngine.setApplyBatchSize(settings.applyBatchSize());
        V26_1_2TerrainEngine.setDebugLogging(settings.debugLogging());
    }

    @Override
    public CompletableFuture<Void> generate(World world, Collection<ChunkCoordinate> chunks, long seed,
                                            IntConsumer progress, Consumer<String> stage,
                                            BooleanSupplier cancelled, int[] extraContextBounds) {
        var positions = chunks.stream().map(chunk -> new ChunkPos(chunk.x(), chunk.z())).toList();
        return V26_1_2TerrainEngine.generate(
            world, positions, seed, progress, stage, cancelled, extraContextBounds);
    }

    @Override
    public void beginBulkSession(int targetCount) {
        V26_1_2TerrainEngine.beginBulkSession(targetCount);
    }

    @Override
    public void endBulkSession() {
        V26_1_2TerrainEngine.endBulkSession();
    }

    @Override
    public int activeGenerationThreads() {
        return V26_1_2TerrainEngine.getActiveGenerationThreads();
    }

    @Override
    public int resolvedParallelism() {
        return V26_1_2TerrainEngine.getResolvedParallelism();
    }

    @Override
    public void shutdown() {
        V26_1_2TerrainEngine.shutdownPool();
    }
}

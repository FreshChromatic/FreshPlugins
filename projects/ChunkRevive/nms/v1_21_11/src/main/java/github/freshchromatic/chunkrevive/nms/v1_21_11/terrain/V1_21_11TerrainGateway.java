package github.freshchromatic.chunkrevive.nms.v1_21_11.terrain;

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

public final class V1_21_11TerrainGateway implements TerrainGateway {
    @Override
    public void initialize(JavaPlugin plugin) {
        V1_21_11TerrainEngine.init(plugin);
    }

    @Override
    public void configure(TerrainSettings settings) {
        V1_21_11TerrainEngine.setEntityExemptions(settings.entityExemptions());
        V1_21_11TerrainEngine.setThreadPoolConfig(settings.threadPool());
        V1_21_11TerrainEngine.setMemorySafetyConfig(settings.memorySafety());
        V1_21_11TerrainEngine.setContextRadius(settings.contextRadius());
        V1_21_11TerrainEngine.setApplyBatchSize(settings.applyBatchSize());
        V1_21_11TerrainEngine.setDebugLogging(settings.debugLogging());
    }

    @Override
    public CompletableFuture<Void> generate(World world, Collection<ChunkCoordinate> chunks, long seed,
                                            IntConsumer progress, Consumer<String> stage,
                                            BooleanSupplier cancelled, int[] extraContextBounds) {
        var positions = chunks.stream().map(chunk -> new ChunkPos(chunk.x(), chunk.z())).toList();
        return V1_21_11TerrainEngine.generate(
            world, positions, seed, progress, stage, cancelled, extraContextBounds);
    }

    @Override
    public void beginBulkSession(int targetCount) {
        V1_21_11TerrainEngine.beginBulkSession(targetCount);
    }

    @Override
    public void endBulkSession() {
        V1_21_11TerrainEngine.endBulkSession();
    }

    @Override
    public int activeGenerationThreads() {
        return V1_21_11TerrainEngine.getActiveGenerationThreads();
    }

    @Override
    public int resolvedParallelism() {
        return V1_21_11TerrainEngine.getResolvedParallelism();
    }

    @Override
    public void shutdown() {
        V1_21_11TerrainEngine.shutdownPool();
    }
}


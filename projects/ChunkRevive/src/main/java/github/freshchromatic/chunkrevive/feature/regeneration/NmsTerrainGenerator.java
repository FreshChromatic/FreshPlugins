package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.nms.TerrainGateway;
import github.freshchromatic.chunkrevive.nms.TerrainSettings;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Stable core facade for the selected version-specific terrain adapter.
 * No NMS or CraftBukkit types are allowed in this class.
 */
public final class NmsTerrainGenerator {
    private static TerrainGateway gateway;
    private static PluginConfig.Structure.EntityExemptions entityExemptions =
        new PluginConfig.Structure.EntityExemptions();
    private static PluginConfig.Regen.ThreadPool threadPool = new PluginConfig.Regen.ThreadPool();
    private static PluginConfig.Regen.MemorySafety memorySafety = new PluginConfig.Regen.MemorySafety();
    private static int contextRadius = 2;
    private static int applyBatchSize = 8;
    private static boolean debugLogging;

    private NmsTerrainGenerator() {}

    public static void init(JavaPlugin plugin) {
        gateway = NmsPlatformLoader.load().terrain();
        gateway.initialize(plugin);
        applySettings();
    }

    public static void setEntityExemptions(PluginConfig.Structure.EntityExemptions value) {
        entityExemptions = value;
        applySettings();
    }

    public static void setThreadPoolConfig(PluginConfig.Regen.ThreadPool value) {
        threadPool = value;
        applySettings();
    }

    public static void setMemorySafetyConfig(PluginConfig.Regen.MemorySafety value) {
        memorySafety = value;
        applySettings();
    }

    public static void setContextRadius(int value) {
        contextRadius = Math.max(2, value);
        applySettings();
    }

    public static void setApplyBatchSize(int value) {
        applyBatchSize = Math.max(1, value);
        applySettings();
    }

    public static void setDebugLogging(boolean enabled) {
        debugLogging = enabled;
        applySettings();
    }

    public static PluginConfig.Regen.ThreadPool getThreadPoolConfig() {
        return threadPool;
    }

    public static int getActiveGenerationThreads() {
        return requireGateway().activeGenerationThreads();
    }

    public static int getResolvedParallelism() {
        return requireGateway().resolvedParallelism();
    }

    public static void beginBulkSession(int targetCount) {
        requireGateway().beginBulkSession(targetCount);
    }

    public static void endBulkSession() {
        requireGateway().endBulkSession();
    }

    public static CompletableFuture<Void> generate(
        World world,
        Collection<ChunkCoordinate> chunks,
        long seed,
        IntConsumer progress,
        Consumer<String> stage,
        BooleanSupplier cancelled,
        int[] extraContextBounds
    ) {
        return requireGateway().generate(
            world, chunks, seed, progress, stage, cancelled, extraContextBounds);
    }

    public static void shutdownPool() {
        if (gateway != null) gateway.shutdown();
    }

    private static TerrainGateway requireGateway() {
        if (gateway == null) throw new IllegalStateException("NMS terrain adapter has not been initialized");
        return gateway;
    }

    private static void applySettings() {
        if (gateway == null) return;
        gateway.configure(new TerrainSettings(
            new TerrainSettings.EntityExemptions(
                entityExemptions.keepRidden,
                entityExemptions.keepLeashed,
                entityExemptions.keepAllayAttracted,
                entityExemptions.keepTamedPets,
                entityExemptions.tamedPetOwnerRadius),
            new TerrainSettings.ThreadPool(
                threadPool.parallelism,
                threadPool.priority,
                threadPool.daemon,
                threadPool.asyncMode),
            new TerrainSettings.MemorySafety(
                memorySafety.enabled,
                memorySafety.maxGenerationThreads),
            contextRadius,
            applyBatchSize,
            debugLogging));
    }
}

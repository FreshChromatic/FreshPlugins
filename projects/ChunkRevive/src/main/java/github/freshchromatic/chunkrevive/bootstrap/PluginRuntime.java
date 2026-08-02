package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.presentation.command.ChunkReviveCommand;
import github.freshchromatic.chunkrevive.presentation.command.KeepCommands;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.presentation.display.ChunkDisplayService;
import github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.structure.StructureProtectionTracker;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureRefreshScheduler;
import github.freshchromatic.chunkrevive.feature.structure.StructureDetector;
import github.freshchromatic.chunkrevive.feature.structure.StructureMarkExpander;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.util.Logging;
import github.freshchromatic.chunkrevive.api.ChunkReviveApi;
import github.freshchromatic.chunkrevive.application.api.DefaultChunkReviveApi;
import github.freshchromatic.chunkrevive.infrastructure.update.ModrinthUpdateChecker;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Owns the live component graph and its reload/shutdown ordering. */
final class PluginRuntime {
    private final ChunkRevivePlugin plugin;
    private final ConfigurateConfigManager<PluginConfig> configManager;
    private final ConfigurateConfigManager<Messages> messagesManager;
    private final Database database;
    private final MarkRegistry markRegistry;
    private final ChunkDisplayService displayService;
    private final StructureRegistry structureRegistry;
    private final StructureRefreshScheduler structureRefreshScheduler;
    private final StructureDetector structureDetector;
    private final StructureMarkExpander structureMarkExpander;
    private final StructureProtectionTracker structureProtectionTracker;
    private final ChunkReviveCommand chunkReviveCommand;
    private final KeepCommands keepCommands;
    private final WorldAccessPolicy worldAccessPolicy;
    private final DiskChunkScanner diskChunkScanner;
    private final DeletionService deletionService;
    private final DefaultChunkReviveApi publicApi;
    private final ModrinthUpdateChecker updateChecker;

    PluginRuntime(
            ChunkRevivePlugin plugin,
            ConfigurateConfigManager<PluginConfig> configManager,
            ConfigurateConfigManager<Messages> messagesManager,
            Database database,
            MarkRegistry markRegistry,
            ChunkDisplayService displayService,
            StructureRegistry structureRegistry,
            StructureDetector structureDetector,
            StructureMarkExpander structureMarkExpander,
            StructureRefreshScheduler structureRefreshScheduler,
            StructureProtectionTracker structureProtectionTracker,
            ChunkReviveCommand chunkReviveCommand,
            KeepCommands keepCommands,
            WorldAccessPolicy worldAccessPolicy,
            DiskChunkScanner diskChunkScanner,
            DeletionService deletionService,
            DefaultChunkReviveApi publicApi,
            ModrinthUpdateChecker updateChecker) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messagesManager = messagesManager;
        this.database = database;
        this.markRegistry = markRegistry;
        this.displayService = displayService;
        this.structureRegistry = structureRegistry;
        this.structureDetector = structureDetector;
        this.structureMarkExpander = structureMarkExpander;
        this.structureRefreshScheduler = structureRefreshScheduler;
        this.structureProtectionTracker = structureProtectionTracker;
        this.chunkReviveCommand = chunkReviveCommand;
        this.keepCommands = keepCommands;
        this.worldAccessPolicy = worldAccessPolicy;
        this.diskChunkScanner = diskChunkScanner;
        this.deletionService = deletionService;
        this.publicApi = publicApi;
        this.updateChecker = updateChecker;
    }

    PluginConfig config() {
        return configManager.config();
    }

    boolean saveAndApplyConfig() {
        var configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        var backupPath = plugin.getDataFolder().toPath().resolve("config.yml.bak");
        try {
            if (Files.exists(configPath)) {
                Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            }
            configManager.save();
        } catch (IOException e) {
            Logging.logger().severe("Failed to back up ChunkRevive configuration: " + e.getMessage());
            return false;
        }

        var config = configManager.config();
        applyTerrainConfig(config);
        markRegistry.setConfig(config);
        diskChunkScanner.setConfig(config);
        return true;
    }

    void reload() {
        configManager.load();
        messagesManager.load();

        var config = configManager.config();
        var messages = messagesManager.config();
        applyTerrainConfig(config);

        markRegistry.setConfig(config);
        markRegistry.setMessages(messages);
        structureRegistry.setConfig(config);
        structureDetector.setConfig(config);
        structureMarkExpander.setConfig(config);
        worldAccessPolicy.setConfig(config);
        diskChunkScanner.setConfig(config);
        deletionService.setConfig(config);
        deletionService.setMessages(messages);

        structureProtectionTracker.setConfig(config);
        structureProtectionTracker.setMessages(messages);
        structureProtectionTracker.start();

        structureRefreshScheduler.setConfig(config);
        structureRefreshScheduler.setMessages(messages);
        structureRefreshScheduler.start();

        displayService.stop();
        displayService.setConfig(config);
        displayService.setMessages(messages);
        displayService.start();

        chunkReviveCommand.setMessages(messages);
        keepCommands.setMessages(messages);
        keepCommands.setConfig(config);
        updateChecker.start(config.updates);
    }

    void stop() {
        // Stop producers before consumers/resources so no task survives into a fresh plugin instance.
        publicApi.deactivate();
        Bukkit.getServicesManager().unregister(ChunkReviveApi.class, publicApi);
        markRegistry.getRegenerationQueue().cancel();
        diskChunkScanner.cancel();
        deletionService.stop();
        structureProtectionTracker.stop();
        structureRefreshScheduler.stop();
        displayService.stop();
        updateChecker.stop();
        database.close();
        NmsTerrainGenerator.shutdownPool();
    }

    static void applyTerrainConfig(PluginConfig config) {
        NmsTerrainGenerator.setEntityExemptions(config.structure.entityExemptions);
        NmsTerrainGenerator.setThreadPoolConfig(config.regen.threadPool);
        NmsTerrainGenerator.setMemorySafetyConfig(config.regen.memorySafety);
        NmsTerrainGenerator.setApplyBatchSize(config.regen.applyBatchSize);
        NmsTerrainGenerator.setContextRadius(config.regen.contextRadius);
        NmsTerrainGenerator.setDebugLogging(config.enableDebugLogs);
    }
}

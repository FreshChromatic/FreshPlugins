package github.freshchromatic.chunkrevive.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.presentation.command.ChunkReviveCommand;
import github.freshchromatic.chunkrevive.presentation.command.KeepCommands;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.infrastructure.persistence.DatabaseFactory;
import github.freshchromatic.chunkrevive.infrastructure.persistence.PersistenceRepository;
import github.freshchromatic.chunkrevive.presentation.display.ChunkDisplayService;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationService;
import github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionIntegrationLoader;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.coordination.ChunkMutationCoordinator;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.structure.StructureProtectionTracker;
import github.freshchromatic.chunkrevive.feature.structure.StructureDetector;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureMarkExpander;
import github.freshchromatic.chunkrevive.feature.structure.StructureRefreshScheduler;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import github.freshchromatic.freshlib.database.Database;
import github.freshchromatic.freshlib.util.Logging;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import github.freshchromatic.chunkrevive.api.ChunkReviveApi;
import github.freshchromatic.chunkrevive.application.api.DefaultChunkReviveApi;
import github.freshchromatic.chunkrevive.application.api.DefaultIntegrationApi;
import github.freshchromatic.chunkrevive.infrastructure.update.ModrinthUpdateChecker;

/** Builds and starts the complete runtime component graph. */
final class ChunkReviveBootstrap {

    private ChunkReviveBootstrap() {
    }

    static PluginRuntime start(ChunkRevivePlugin plugin) {
        Logging.init(plugin);
        plugin.getDataFolder().mkdirs();

        var configManager = configManager(plugin);
        var messagesManager = messagesManager(plugin);
        if (!configManager.tryLoad() || !messagesManager.tryLoad()) {
            Logging.logger().severe("Cannot load ChunkRevive configuration; disabling ChunkRevive.");
            return null;
        }
        var config = configManager.config();
        var messages = messagesManager.config();
        NmsTerrainGenerator.init(plugin);
        PluginRuntime.applyTerrainConfig(config);

        boolean packetEventsAvailable = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        if (packetEventsAvailable) {
            EntityLib.init(new SpigotEntityLibPlatform(plugin), new APIConfig(PacketEvents.getAPI()).disableBStats());
        } else if (config.display.enabled) {
            Logging.logger().warning(
                "PacketEvents is not installed or enabled; marked-chunk floating displays are disabled.");
        }

        Database database;
        try {
            database = DatabaseFactory.create(plugin, config.database);
        } catch (IllegalArgumentException failure) {
            Logging.logger().severe("Invalid ChunkRevive database configuration; disabling ChunkRevive.", failure);
            NmsTerrainGenerator.shutdownPool();
            return null;
        }
        if (!database.connect()) {
            Logging.logger().severe("Cannot connect to database — disabling ChunkRevive.");
            database.close();
            NmsTerrainGenerator.shutdownPool();
            return null;
        }

        var repository = new PersistenceRepository(database);
        try {
            repository.init();
        } catch (IllegalStateException failure) {
            Logging.logger().severe("Cannot initialize ChunkRevive database; disabling ChunkRevive.", failure);
            database.close();
            NmsTerrainGenerator.shutdownPool();
            return null;
        }

        var protectionIntegration = ProtectionIntegrationLoader.load(() -> configManager.config().landProtection);
        var landProtection = protectionIntegration.landProtection();
        var integrationApi = new DefaultIntegrationApi();
        var worldAccessPolicy = new WorldAccessPolicy(config);

        var structureRegistry = new StructureRegistry(repository, config, landProtection);
        var structureDetector = new StructureDetector(config);
        var structureMarkExpander = new StructureMarkExpander(config, structureDetector, structureRegistry,
            landProtection);

        var chunkMutationCoordinator = new ChunkMutationCoordinator();
        var regenerationService = new RegenerationService(plugin, messages, landProtection, integrationApi,
            chunkMutationCoordinator);
        var markRegistry = new MarkRegistry(repository, regenerationService, config, messages, landProtection);
        markRegistry.setStructureMarkExpander(structureMarkExpander);
        markRegistry.setStructureRegistry(structureRegistry);
        try {
            structureRegistry.loadFromDatabase();
            markRegistry.loadFromDatabase();
        } catch (IllegalStateException failure) {
            Logging.logger().severe("Cannot load ChunkRevive data; disabling ChunkRevive.", failure);
            database.close();
            NmsTerrainGenerator.shutdownPool();
            return null;
        }
        markRegistry.getRegenerationQueue().setPlugin(plugin);

        var displayService = new ChunkDisplayService(plugin, markRegistry, config, messages, packetEventsAvailable);
        displayService.setStructureRegistry(structureRegistry);
        markRegistry.setMarkDisplay(displayService);

        var protectedMarks = new ProtectedMarkExecutor(plugin, markRegistry, integrationApi);
        var diskChunkScanner = new DiskChunkScanner(markRegistry, structureRegistry, landProtection, config,
            integrationApi, plugin);
        var chunkScanService = new ChunkScanService(markRegistry, diskChunkScanner,
            NmsPlatformLoader.load().worldScan(), configManager::config, protectedMarks);

        var deletionService = new DeletionService(plugin, markRegistry, repository, config, messages, integrationApi,
            chunkMutationCoordinator);

        var markService = new MarkService(markRegistry, worldAccessPolicy, protectedMarks);
        var structureService = new StructureService(markRegistry, structureRegistry, structureDetector,
            worldAccessPolicy, protectedMarks);
        var resetService = new ResetService(markRegistry, structureRegistry, worldAccessPolicy, deletionService,
            configManager::config, task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task));

        DefaultChunkReviveApi publicApi;
        try {
            publicApi = new DefaultChunkReviveApi(plugin, markRegistry, worldAccessPolicy, resetService,
                deletionService, chunkScanService, structureService, integrationApi, repository, protectedMarks);
        } catch (RuntimeException failure) {
            Logging.logger().severe("Cannot load ChunkRevive operation history; disabling ChunkRevive.", failure);
            database.close();
            NmsTerrainGenerator.shutdownPool();
            return null;
        }
        displayService.start();
        deletionService.start();

        var chunkReviveCommand = new ChunkReviveCommand(plugin, markRegistry, messages, structureRegistry,
            worldAccessPolicy, diskChunkScanner, deletionService, markService, chunkScanService, resetService,
            structureService, publicApi::deletionJobsCancelled);
        chunkReviveCommand.register();

        var structureProtectionTracker = new StructureProtectionTracker(plugin, config, messages, markRegistry,
            structureRegistry);
        structureProtectionTracker.start();
        var keepCommands = new KeepCommands(plugin, messages, config, structureProtectionTracker);
        keepCommands.register();

        var refreshScheduler = new StructureRefreshScheduler(plugin, config, messages, structureRegistry, markRegistry,
            worldAccessPolicy, protectedMarks);
        refreshScheduler.start();

        var updateChecker = new ModrinthUpdateChecker(plugin, messagesManager::config);
        Bukkit.getPluginManager().registerEvents(updateChecker, plugin);
        updateChecker.start(config.updates);

        ListenerRegistrar.register(plugin, markService, structureService, displayService, messagesManager::config,
            configManager::config, protectionIntegration);

        Bukkit.getPluginManager().registerEvents(integrationApi, plugin);
        Bukkit.getServicesManager().register(ChunkReviveApi.class, publicApi, plugin, ServicePriority.Normal);
        protectionIntegration.registerProviders(integrationApi.builtInRegistrar());

        return new PluginRuntime(plugin, configManager, messagesManager, repository, markRegistry, displayService,
            structureRegistry, structureDetector, structureMarkExpander, refreshScheduler, structureProtectionTracker,
            chunkReviveCommand, keepCommands, worldAccessPolicy, diskChunkScanner, deletionService, publicApi,
            updateChecker);
    }

    private static ConfigurateConfigManager<PluginConfig> configManager(ChunkRevivePlugin plugin) {
        return new ChunkReviveConfigManager(plugin.getDataFolder().toPath().resolve("config.yml"),
            plugin.getPluginMeta().getVersion());
    }

    private static ConfigurateConfigManager<Messages> messagesManager(ChunkRevivePlugin plugin) {
        return new ConfigurateConfigManager<>(plugin.getDataFolder().toPath().resolve("messages.yml"), Messages.class) {

            @Override
            protected String header() {
                return "ChunkRevive Messages File\nVersion: " + plugin.getPluginMeta().getVersion() + "\n";
            }
        };
    }
}

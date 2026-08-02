package github.freshchromatic.chunkrevive.bootstrap;

import com.github.retrooper.packetevents.PacketEvents;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
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
import github.freshchromatic.chunkrevive.bootstrap.ListenerRegistrar;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.structure.StructureProtectionTracker;
import github.freshchromatic.chunkrevive.feature.structure.StructureDetector;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.feature.structure.StructureMarkExpander;
import github.freshchromatic.chunkrevive.feature.structure.StructureRefreshScheduler;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import github.freshchromatic.freshlib.util.Logging;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import github.freshchromatic.chunkrevive.api.ChunkReviveApi;
import github.freshchromatic.chunkrevive.application.api.DefaultChunkReviveApi;
import github.freshchromatic.chunkrevive.application.api.DefaultIntegrationApi;
import github.freshchromatic.chunkrevive.integration.residence.ResidenceIntegration;
import github.freshchromatic.chunkrevive.infrastructure.update.ModrinthUpdateChecker;

/** Builds and starts the complete runtime component graph. */
final class ChunkReviveBootstrap {
    private ChunkReviveBootstrap() {}

    static PluginRuntime start(ChunkRevivePlugin plugin) {
        Logging.init(plugin);
        NmsTerrainGenerator.init(plugin);
        plugin.getDataFolder().mkdirs();

        var configManager = configManager(plugin);
        var messagesManager = messagesManager(plugin);
        configManager.load();
        messagesManager.load();
        var config = configManager.config();
        var messages = messagesManager.config();
        PluginRuntime.applyTerrainConfig(config);

        boolean packetEventsAvailable = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        if (packetEventsAvailable) {
            EntityLib.init(new SpigotEntityLibPlatform(plugin),
                new APIConfig(PacketEvents.getAPI()).disableBStats());
        } else if (config.display.enabled) {
            Logging.logger().warning("PacketEvents is not installed or enabled; marked-chunk floating displays are disabled.");
        }

        var database = DatabaseFactory.create(plugin, config.database);
        if (!database.connect()) {
            Logging.logger().severe("Cannot connect to database — disabling ChunkRevive.");
            database.close();
            NmsTerrainGenerator.shutdownPool();
            return null;
        }

        var repository = new PersistenceRepository(database);
        repository.init();

        var protectionIntegration = ProtectionIntegrationLoader.load();
        var landProtection = protectionIntegration.landProtection();
        var worldAccessPolicy = new WorldAccessPolicy(config);

        var structureRegistry = new StructureRegistry(repository, config, landProtection);
        structureRegistry.loadFromDatabase();
        var structureDetector = new StructureDetector(config);
        var structureMarkExpander = new StructureMarkExpander(
            config, structureDetector, structureRegistry, landProtection);

        var regenerationService = new RegenerationService(messages, landProtection);
        var markRegistry = new MarkRegistry(
            repository, regenerationService, config, messages, landProtection);
        markRegistry.setStructureMarkExpander(structureMarkExpander);
        markRegistry.setStructureRegistry(structureRegistry);
        markRegistry.loadFromDatabase();
        markRegistry.getRegenerationQueue().setPlugin(plugin);

        var displayService = new ChunkDisplayService(
            plugin, markRegistry, config, messages, packetEventsAvailable);
        displayService.setStructureRegistry(structureRegistry);
        displayService.start();
        markRegistry.setMarkDisplay(displayService);

        var diskChunkScanner = new DiskChunkScanner(
            markRegistry, structureRegistry, landProtection, config);
        var chunkScanService = new ChunkScanService(
            markRegistry, diskChunkScanner, NmsPlatformLoader.load().worldScan(), configManager::config);

        var deletionService = new DeletionService(
            plugin, markRegistry, repository, config, messages);
        deletionService.start();

        var markService = new MarkService(markRegistry, worldAccessPolicy);
        var structureService = new StructureService(
            markRegistry, structureRegistry, structureDetector, worldAccessPolicy);
        var resetService = new ResetService(
            markRegistry, structureRegistry, worldAccessPolicy,
            deletionService, configManager::config);

        var chunkReviveCommand = new ChunkReviveCommand(
            plugin, markRegistry, messages, structureRegistry, worldAccessPolicy,
            diskChunkScanner, deletionService, markService, chunkScanService,
            resetService, structureService);
        chunkReviveCommand.register();

        var structureProtectionTracker = new StructureProtectionTracker(
            plugin, config, messages, markRegistry, structureRegistry);
        structureProtectionTracker.start();
        var keepCommands = new KeepCommands(plugin, messages, config, structureProtectionTracker);
        keepCommands.register();

        var refreshScheduler = new StructureRefreshScheduler(
            plugin, config, messages, structureRegistry, markRegistry, worldAccessPolicy);
        refreshScheduler.start();

        var updateChecker = new ModrinthUpdateChecker(plugin, messagesManager::config);
        Bukkit.getPluginManager().registerEvents(updateChecker, plugin);
        updateChecker.start(config.updates);

        ListenerRegistrar.register(
            plugin, markService, structureService, displayService,
            messagesManager::config, configManager::config, protectionIntegration);

        var integrationApi = new DefaultIntegrationApi();
        Bukkit.getPluginManager().registerEvents(integrationApi, plugin);
        var publicApi = new DefaultChunkReviveApi(plugin, markRegistry, worldAccessPolicy, resetService, deletionService, chunkScanService, structureService, integrationApi, repository);
        Bukkit.getServicesManager().register(ChunkReviveApi.class, publicApi, plugin, ServicePriority.Normal);
        if (protectionIntegration instanceof ResidenceIntegration residenceIntegration) {
            var residence = Bukkit.getPluginManager().getPlugin("Residence");
            if (residence != null) residenceIntegration.registerProvider(integrationApi, residence);
        }

        return new PluginRuntime(
            plugin, configManager, messagesManager, database, markRegistry, displayService,
            structureRegistry, structureDetector, structureMarkExpander, refreshScheduler,
            structureProtectionTracker, chunkReviveCommand, keepCommands,
            worldAccessPolicy, diskChunkScanner, deletionService, publicApi, updateChecker);
    }

    private static ConfigurateConfigManager<PluginConfig> configManager(ChunkRevivePlugin plugin) {
        return new ChunkReviveConfigManager(
            plugin.getDataFolder().toPath().resolve("config.yml"),
            plugin.getPluginMeta().getVersion());
    }

    private static ConfigurateConfigManager<Messages> messagesManager(ChunkRevivePlugin plugin) {
        return new ConfigurateConfigManager<>(
                plugin.getDataFolder().toPath().resolve("messages.yml"), Messages.class) {
            @Override
            protected String header() {
                return "ChunkRevive Messages File\nVersion: "
                    + plugin.getPluginMeta().getVersion() + "\n";
            }
        };
    }
}

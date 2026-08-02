package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.presentation.display.ChunkDisplayService;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionIntegration;
import github.freshchromatic.chunkrevive.feature.marking.PlayerMarkingListener;
import github.freshchromatic.chunkrevive.presentation.display.PlayerLifecycleListener;
import github.freshchromatic.chunkrevive.presentation.display.PlayerVisibilityListener;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Single registration point for core and optional-integration listeners. */
public final class ListenerRegistrar {
    private ListenerRegistrar() {}

    public static void register(
            Plugin plugin,
            MarkService markService,
            StructureService structureService,
            ChunkDisplayService displayService,
            Supplier<Messages> messages,
            Supplier<PluginConfig> config,
            ProtectionIntegration protectionIntegration) {
        var markingListener =
            new PlayerMarkingListener(markService, messages, config, structureService, plugin);
        Bukkit.getPluginManager().registerEvents(markingListener, plugin);
        Bukkit.getOnlinePlayers().forEach(player ->
            Scheduler.runTask(plugin,
                () -> markingListener.detectStructuresAtCurrentLocation(player), player));
        Bukkit.getPluginManager().registerEvents(
            new PlayerVisibilityListener(displayService),
            plugin);
        Bukkit.getPluginManager().registerEvents(
            new PlayerLifecycleListener(plugin, markService, displayService),
            plugin);
        protectionIntegration.registerListeners(plugin, markService);
    }
}

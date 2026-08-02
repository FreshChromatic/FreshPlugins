package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.api.event.OperationCompletedEvent;
import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Anonymous, opt-out bStats reporting for aggregate configuration and operation usage. */
final class BStatsMetrics implements Listener {
    private static final int PLUGIN_ID = 33087;

    private final ChunkRevivePlugin plugin;
    private final AtomicInteger completedScans = new AtomicInteger();
    private final AtomicInteger completedRegenerations = new AtomicInteger();
    private final AtomicInteger completedResets = new AtomicInteger();

    private BStatsMetrics(ChunkRevivePlugin plugin) {
        this.plugin = plugin;
    }

    static void start(ChunkRevivePlugin plugin) {
        var reporter = new BStatsMetrics(plugin);
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(reporter, plugin);

        var metrics = new Metrics(plugin, PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("database_type", reporter::databaseType));
        metrics.addCustomChart(new SingleLineChart("completed_scan_operations", reporter.completedScans::get));
        metrics.addCustomChart(new SingleLineChart("completed_regenerate_operations", reporter.completedRegenerations::get));
        metrics.addCustomChart(new SingleLineChart("completed_reset_operations", reporter.completedResets::get));
    }

    @EventHandler
    public void onOperationCompleted(OperationCompletedEvent event) {
        OperationSnapshot operation = event.operation();
        if (operation.state() != OperationState.SUCCEEDED
            && operation.state() != OperationState.PARTIALLY_SUCCEEDED) return;

        OperationType type = operation.type();
        if (type == OperationType.SCAN_EXISTING_CHUNKS || type == OperationType.SCAN_BIOMES) {
            completedScans.incrementAndGet();
        } else if (type == OperationType.REGENERATE) {
            completedRegenerations.incrementAndGet();
        } else if (type == OperationType.RESET) {
            completedResets.incrementAndGet();
        }
    }

    private String databaseType() {
        String configuredType = plugin.getPluginConfig().database.type;
        if (configuredType == null || configuredType.isBlank()) return "unknown";
        return configuredType.trim().toLowerCase(Locale.ROOT);
    }
}

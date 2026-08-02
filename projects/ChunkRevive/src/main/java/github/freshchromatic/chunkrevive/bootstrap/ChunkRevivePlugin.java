package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Bukkit lifecycle entry point; runtime construction and ownership live in bootstrap components. */
public final class ChunkRevivePlugin extends JavaPlugin {
    private PluginRuntime runtime;

    @Override
    public void onEnable() {
        runtime = ChunkReviveBootstrap.start(this);
        if (runtime == null) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        BStatsMetrics.start(this);
        Logging.logger().info("ChunkRevive enabled.");
    }

    public PluginConfig getPluginConfig() {
        return requireRuntime().config();
    }

    public boolean saveAndApplyConfig() {
        return requireRuntime().saveAndApplyConfig();
    }

    public void reload() {
        requireRuntime().reload();
    }

    @Override
    public void onDisable() {
        if (runtime == null) return;
        runtime.stop();
        runtime = null;
        Logging.logger().info("ChunkRevive disabled.");
    }

    private PluginRuntime requireRuntime() {
        if (runtime == null) throw new IllegalStateException("ChunkRevive runtime is not active");
        return runtime;
    }
}

package github.freshchromatic.freshlib;

import org.bukkit.plugin.java.JavaPlugin;

public final class FreshLib {

    private static FreshLib instance;

    private final JavaPlugin plugin;

    private FreshLib(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Bound once by {@link FreshLibPlugin#onEnable()}. FreshLib runs as a single
     * shared instance across the server (via joinClasspath), so this must not be
     * called by consuming plugins.
     */
    static void bind(JavaPlugin plugin) {
        instance = new FreshLib(plugin);
    }

    public static FreshLib getInstance() {
        return instance;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }
}

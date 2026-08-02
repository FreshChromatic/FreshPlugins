package github.freshchromatic.freshlib;

import github.freshchromatic.freshlib.item.PlayerInteractListener;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreshLibPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 33088;

    @Override
    public void onEnable() {
        FreshLib.bind(this);
        Bukkit.getPluginManager().registerEvents(new PlayerInteractListener(), this);
        new Metrics(this, BSTATS_PLUGIN_ID);
    }
}

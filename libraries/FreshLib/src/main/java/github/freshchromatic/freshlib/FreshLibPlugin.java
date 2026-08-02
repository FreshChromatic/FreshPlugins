package github.freshchromatic.freshlib;

import github.freshchromatic.freshlib.item.PlayerInteractListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreshLibPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        FreshLib.bind(this);
        Bukkit.getPluginManager().registerEvents(new PlayerInteractListener(), this);
    }
}

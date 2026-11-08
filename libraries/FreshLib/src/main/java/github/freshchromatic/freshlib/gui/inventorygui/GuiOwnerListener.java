package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

final class GuiOwnerListener implements Listener {

    private final Plugin          plugin;
    private final GuiOwnerActions gui;
    private final Runnable        refresh;

    private boolean registered;

    GuiOwnerListener(Plugin plugin, GuiOwnerActions gui, Runnable refresh) {
        this.plugin = plugin;
        this.gui = gui;
        this.refresh = refresh;
    }

    synchronized void register() {
        if (registered) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
    }

    synchronized void unregister() {
        if (!registered) {
            return;
        }
        HandlerList.unregisterAll(this);
        registered = false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (gui.hasRealOwner() && (gui.getOwner().equals(event.getDestination().getHolder()) || gui.getOwner().equals(
            event.getSource().getHolder()))) {
            refresh.run();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (gui.hasRealOwner() && gui.getOwner().equals(event.getBlock().getState())) {
            refresh.run();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (gui.hasRealOwner() && gui.getOwner().equals(event.getBlock().getState())) {
            gui.destroy();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (gui.hasRealOwner() && gui.getOwner().equals(event.getEntity())) {
            gui.destroy();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            gui.destroy();
        }
    }
}

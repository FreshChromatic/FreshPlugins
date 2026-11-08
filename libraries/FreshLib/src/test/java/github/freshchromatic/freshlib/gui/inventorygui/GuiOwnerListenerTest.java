package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiOwnerListenerTest {

    @Test
    void registeringOwnerEventsMoreThanOnceAddsOnlyOneListener() {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        var listener = new GuiOwnerListener(plugin, mock(GuiOwnerActions.class), mock(Runnable.class));

        listener.register();
        listener.register();

        verify(pluginManager, times(1)).registerEvents(listener, plugin);
    }

    @Test
    void ownerInventoryChangesScheduleGuiRefresh() {
        var plugin = mock(Plugin.class);
        var gui = mock(GuiOwnerActions.class);
        var owner = mock(InventoryHolder.class);
        var event = mock(InventoryMoveItemEvent.class);
        var destination = mock(Inventory.class);
        var source = mock(Inventory.class);
        var refresh = mock(Runnable.class);
        when(gui.hasRealOwner()).thenReturn(true);
        when(gui.getOwner()).thenReturn(owner);
        when(event.getDestination()).thenReturn(destination);
        when(event.getSource()).thenReturn(source);
        when(destination.getHolder()).thenReturn(null);
        when(source.getHolder()).thenReturn(owner);
        var listener = new GuiOwnerListener(plugin, gui, refresh);

        listener.onInventoryMoveItem(event);

        verify(refresh).run();
    }

    @Test
    void ownerRemovalAndPluginDisableDestroyTheGui() {
        var plugin = mock(Plugin.class);
        var gui = mock(GuiOwnerActions.class);
        var owner = mock(Chest.class);
        var block = mock(Block.class);
        var blockBreak = mock(BlockBreakEvent.class);
        when(gui.hasRealOwner()).thenReturn(true);
        when(gui.getOwner()).thenReturn(owner);
        when(blockBreak.getBlock()).thenReturn(block);
        when(block.getState()).thenReturn(owner);
        var listener = new GuiOwnerListener(plugin, gui, mock(Runnable.class));

        listener.onBlockBreak(blockBreak);

        verify(gui).destroy();
    }

    @Test
    void unrelatedPluginDisableDoesNotDestroyTheGui() {
        var plugin = mock(Plugin.class);
        var gui = mock(GuiOwnerActions.class);
        var event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(mock(Plugin.class));
        var listener = new GuiOwnerListener(plugin, gui, mock(Runnable.class));

        listener.onPluginDisable(event);

        verify(gui, never()).destroy();
    }

    @Test
    void disablingTheOwningPluginDestroysTheGui() {
        var plugin = mock(Plugin.class);
        var gui = mock(GuiOwnerActions.class);
        var event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(plugin);
        var listener = new GuiOwnerListener(plugin, gui, mock(Runnable.class));

        listener.onPluginDisable(event);

        verify(gui).destroy();
    }
}

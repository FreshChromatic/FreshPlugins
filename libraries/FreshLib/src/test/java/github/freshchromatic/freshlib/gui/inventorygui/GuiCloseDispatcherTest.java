package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiCloseDispatcherTest {

    @Test
    void closeEventClearsHistoryAndCleansUpWhenTransitionFails() {
        var calls = new ArrayList<String>();
        var failure = new IllegalStateException("close callback failed");
        var dispatcher = new GuiCloseDispatcher((viewer, task) -> {
        });

        var thrown = assertThrows(IllegalStateException.class, () -> dispatcher.handleCloseEvent(() -> {
            calls.add("history");
            throw failure;
        }, () -> calls.add("clear history"), () -> calls.add("cleanup")));

        assertSame(failure, thrown);
        assertEquals(List.of("history", "clear history", "cleanup"), calls);
    }

    @Test
    void closesViewerThroughItsEntityScheduler() {
        HumanEntity viewer = mock(HumanEntity.class);
        AtomicReference<HumanEntity> scheduledViewer = new AtomicReference<>();
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        var dispatcher = new GuiCloseDispatcher((entity, task) -> {
            scheduledViewer.set(entity);
            scheduledTask.set(task);
        });

        dispatcher.close(viewer);

        assertSame(viewer, scheduledViewer.get());
        verify(viewer, never()).closeInventory();
        scheduledTask.get().run();
        verify(viewer).closeInventory();
    }

    @Test
    void closesAndClearsTheGuiInsideTheViewerTask() {
        HumanEntity viewer = mock(HumanEntity.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        var dispatcher = new GuiCloseDispatcher((entity, task) -> scheduledTask.set(task));

        dispatcher.closeAndClear(viewer, inventory);

        verify(viewer, never()).closeInventory();
        verify(inventory, never()).clear();
        scheduledTask.get().run();
        verify(viewer).closeInventory();
        verify(inventory).clear();
    }

    @Test
    void clearsInventoryThroughTheViewerTask() {
        HumanEntity viewer = mock(HumanEntity.class);
        Inventory inventory = mock(Inventory.class);
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        var dispatcher = new GuiCloseDispatcher((entity, task) -> scheduledTask.set(task));

        dispatcher.clear(viewer, inventory);

        verify(inventory, never()).clear();
        scheduledTask.get().run();
        verify(inventory).clear();
    }

    @Test
    void doesNotCloseAViewerThatHasAlreadyOpenedAnotherInventory() {
        HumanEntity viewer = mock(HumanEntity.class);
        Inventory inventory = mock(Inventory.class);
        Inventory otherInventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(otherInventory);
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        var dispatcher = new GuiCloseDispatcher((entity, task) -> scheduledTask.set(task));

        dispatcher.closeAndClear(viewer, inventory);
        scheduledTask.get().run();

        verify(viewer, never()).closeInventory();
        verify(inventory).clear();
    }
}

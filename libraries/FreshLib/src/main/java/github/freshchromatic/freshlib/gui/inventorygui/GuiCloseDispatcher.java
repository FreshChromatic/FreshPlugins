package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;

import java.util.function.BiConsumer;

final class GuiCloseDispatcher {

    private final BiConsumer<HumanEntity, Runnable> taskScheduler;

    GuiCloseDispatcher(BiConsumer<HumanEntity, Runnable> taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    void close(HumanEntity viewer) {
        taskScheduler.accept(viewer, viewer::closeInventory);
    }

    void handleCloseEvent(Runnable historyTransition, Runnable clearHistory, Runnable cleanup) {
        try {
            historyTransition.run();
        } catch (RuntimeException | Error failure) {
            clearHistory.run();
            throw failure;
        } finally {
            cleanup.run();
        }
    }

    void closeAndClear(HumanEntity viewer, Inventory inventory) {
        taskScheduler.accept(viewer, () -> {
            if (viewer.getOpenInventory().getTopInventory().equals(inventory)) {
                viewer.closeInventory();
            }
            inventory.clear();
        });
    }

    void clear(HumanEntity viewer, Inventory inventory) {
        taskScheduler.accept(viewer, inventory::clear);
    }
}

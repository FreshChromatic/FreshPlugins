package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

final class GuiDragDispatcher {

    private final IntFunction<GuiElement>           elementAt;
    private final DragClickHandler                  clickHandler;
    private final BiConsumer<HumanEntity, Runnable> taskScheduler;

    GuiDragDispatcher(IntFunction<GuiElement> elementAt, DragClickHandler clickHandler,
        BiConsumer<HumanEntity, Runnable> taskScheduler) {
        this.elementAt = elementAt;
        this.clickHandler = clickHandler;
        this.taskScheduler = taskScheduler;
    }

    void dispatch(InventoryDragEvent event, Inventory inventory) {
        if (event.getRawSlots().size() == 1) {
            int slot = event.getRawSlots().iterator().next();
            int size;
            try {
                size = GuiView.of(event.getView()).getTopInventory().getSize();
            } catch (RuntimeException | AssertionError | LinkageError failure) {
                event.setCancelled(true);
                return;
            }
            if (slot < size) {
                GuiElement.Click click = clickHandler.handle(event, event.getType() == DragType.SINGLE ? ClickType.RIGHT
                    : ClickType.LEFT, slot, event.getOldCursor());
                if (click != null && !event.getOldCursor().equals(click.getCursor())) {
                    event.setCursor(click.getCursor());
                }
            }
            return;
        }

        int rest = 0;
        Map<Integer, ItemStack> resetSlots = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> item : event.getNewItems().entrySet()) {
            if (item.getKey() < inventory.getSize()) {
                GuiElement element = elementAt.apply(item.getKey());
                if (!(element instanceof GuiStorageElement storageElement) || !storageElement.setStorageItem(
                    event.getWhoClicked(), item.getKey(), item.getValue())) {
                    ItemStack slotItem = event.getInventory().getItem(item.getKey());
                    if (!item.getValue().isSimilar(slotItem)) {
                        rest += item.getValue().getAmount();
                    } else if (slotItem != null) {
                        rest += item.getValue().getAmount() - slotItem.getAmount();
                    }
                    resetSlots.put(item.getKey(), event.getInventory().getItem(item.getKey()));
                }
            }
        }

        taskScheduler.accept(event.getWhoClicked(), () -> {
            for (Map.Entry<Integer, ItemStack> item : resetSlots.entrySet()) {
                GuiView.of(event.getView()).getTopInventory().setItem(item.getKey(), item.getValue());
            }
        });

        if (rest > 0) {
            int cursorAmount = event.getCursor() != null ? event.getCursor().getAmount() : 0;
            if (!event.getOldCursor().isSimilar(event.getCursor())) {
                event.setCursor(event.getOldCursor());
                cursorAmount = 0;
            }
            ItemStack cursor = event.getCursor();
            int newCursorAmount = cursorAmount + rest;
            if (newCursorAmount <= cursor.getMaxStackSize()) {
                cursor.setAmount(newCursorAmount);
            } else {
                cursor.setAmount(cursor.getMaxStackSize());
                ItemStack overflow = cursor.clone();
                int overflowAmount = newCursorAmount - cursor.getMaxStackSize();
                if (overflowAmount > 0) {
                    overflow.setAmount(overflowAmount);
                    for (ItemStack drop : event.getWhoClicked().getInventory().addItem(overflow).values()) {
                        event.getWhoClicked()
                            .getLocation()
                            .getWorld()
                            .dropItem(event.getWhoClicked().getLocation(), drop);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface DragClickHandler {

        GuiElement.Click handle(InventoryDragEvent event, ClickType clickType, int slot, ItemStack cursor);
    }
}

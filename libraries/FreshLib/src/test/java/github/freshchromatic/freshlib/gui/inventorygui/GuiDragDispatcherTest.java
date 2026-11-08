package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiDragDispatcherTest {

    @Test
    void singleSlotDragUsesTheClickTypeForItsDragDirection() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory topInventory = mock(Inventory.class);
        GuiView guiView = mock(GuiView.class);
        ItemStack cursor = mock(ItemStack.class);
        AtomicReference<ClickType> handledClickType = new AtomicReference<>();
        AtomicReference<Integer> handledSlot = new AtomicReference<>();
        when(event.getView()).thenReturn(inventoryView);
        when(event.getRawSlots()).thenReturn(Set.of(2));
        when(event.getType()).thenReturn(DragType.SINGLE);
        when(event.getOldCursor()).thenReturn(cursor);
        when(guiView.getTopInventory()).thenReturn(topInventory);
        when(topInventory.getSize()).thenReturn(9);
        GuiDragDispatcher dispatcher = new GuiDragDispatcher(slot -> null, (rawEvent, clickType, slot, oldCursor) -> {
            handledClickType.set(clickType);
            handledSlot.set(slot);
            return null;
        }, (player, task) -> {
        });

        try (MockedStatic<GuiView> views = mockStatic(GuiView.class)) {
            views.when(() -> GuiView.of(inventoryView)).thenReturn(guiView);

            dispatcher.dispatch(event, topInventory);
        }

        assertEquals(ClickType.RIGHT, handledClickType.get());
        assertEquals(2, handledSlot.get());
        verify(event, never()).setCursor(cursor);
    }

    @Test
    void rejectedDragReturnsItemsToTheExistingCursorStack() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        Inventory inventory = mock(Inventory.class);
        HumanEntity player = mock(HumanEntity.class);
        ItemStack oldCursor = mock(ItemStack.class);
        ItemStack cursor = mock(ItemStack.class);
        ItemStack draggedItem = mock(ItemStack.class);
        ItemStack slotItem = mock(ItemStack.class);
        when(event.getRawSlots()).thenReturn(Set.of(0, 1));
        when(event.getNewItems()).thenReturn(Map.of(0, draggedItem));
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getOldCursor()).thenReturn(oldCursor);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getItem(0)).thenReturn(slotItem);
        when(draggedItem.isSimilar(slotItem)).thenReturn(false);
        when(draggedItem.getAmount()).thenReturn(4);
        when(oldCursor.isSimilar(cursor)).thenReturn(true);
        when(cursor.getAmount()).thenReturn(3);
        when(cursor.getMaxStackSize()).thenReturn(64);
        GuiDragDispatcher dispatcher = new GuiDragDispatcher(slot -> null, (rawEvent, clickType, slot, item) -> null, (
            entity, task) -> {
            });

        dispatcher.dispatch(event, inventory);

        verify(cursor).setAmount(7);
        verify(event, never()).setCursor(oldCursor);
    }
}

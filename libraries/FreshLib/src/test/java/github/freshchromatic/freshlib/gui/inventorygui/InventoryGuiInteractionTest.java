package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryGuiInteractionTest {

    @Test
    void actionReceivesSlotAndClickTypeAndCanAllowTheEvent() {
        var player = mock(Player.class);
        var event = mock(InventoryInteractEvent.class);
        var cursor = mock(ItemStack.class);
        var element = new TestElement('x', click -> {
            assertEquals(1, click.getSlot());
            assertEquals(ClickType.RIGHT, click.getType());
            assertSame(player, click.getWhoClicked());
            assertSame(cursor, click.getCursor());
            return false;
        });
        when(event.getWhoClicked()).thenReturn(player);

        var click = GuiClickDispatcher.dispatch(null, event, ClickType.RIGHT, 1, cursor, element, null);

        assertNotNull(click);
        assertSame(element, click.getElement());
        verify(event, never()).setCancelled(true);
        verify(player, never()).updateInventory();
    }

    @Test
    void missingActionCancelsTheEventAndRefreshesThePlayerInventory() {
        var player = mock(Player.class);
        var event = mock(InventoryInteractEvent.class);
        var element = new TestElement('x', null);
        when(event.getWhoClicked()).thenReturn(player);

        var click = GuiClickDispatcher.dispatch(null, event, ClickType.LEFT, 1, null, element, null);

        assertNull(click);
        verify(event).setCancelled(true);
        verify(player).updateInventory();
    }

    @Test
    void actionCanCancelTheEventAndRefreshThePlayerInventory() {
        var player = mock(Player.class);
        var event = mock(InventoryInteractEvent.class);
        var element = new TestElement('x', click -> true);
        when(event.getWhoClicked()).thenReturn(player);

        var click = GuiClickDispatcher.dispatch(null, event, ClickType.LEFT, 0, null, element, null);

        assertNotNull(click);
        verify(event).setCancelled(true);
        verify(player).updateInventory();
    }

    private static final class TestElement extends GuiElement {

        TestElement(char slot, Action action) {
            super(slot, action);
        }

        @Override
        public ItemStack getItem(org.bukkit.entity.HumanEntity who, int slot) {
            return null;
        }
    }
}

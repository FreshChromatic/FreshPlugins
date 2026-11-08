package github.freshchromatic.freshlib.item;

import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInteractListenerTest {

    @Test
    void ignoresOffHandAndEmptyMainHand() {
        var listener = new PlayerInteractListener();
        var offHand = mock(PlayerInteractEvent.class);
        when(offHand.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.onPlayerInteract(offHand);
        verify(offHand, never()).getItem();

        var emptyHand = mock(PlayerInteractEvent.class);
        when(emptyHand.getHand()).thenReturn(EquipmentSlot.HAND);
        listener.onPlayerInteract(emptyHand);
        verify(emptyHand).getItem();
    }

    @Test
    void ignoresItemsWithoutMetadata() {
        var event = mock(PlayerInteractEvent.class);
        var item = mock(ItemStack.class);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getItem()).thenReturn(item);
        new PlayerInteractListener().onPlayerInteract(event);
        verify(item).getItemMeta();
    }
}

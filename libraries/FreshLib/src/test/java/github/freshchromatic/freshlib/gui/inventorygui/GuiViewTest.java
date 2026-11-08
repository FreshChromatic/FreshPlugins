package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuiViewTest {

    @Test
    void setPropertyInvokesTheWrappedInventoryView() {
        var inventoryView = mock(InventoryView.class);
        when(inventoryView.setProperty(null, 17)).thenReturn(true);

        assertTrue(GuiView.of(inventoryView).setProperty(null, 17));

        verify(inventoryView).setProperty(null, 17);
    }
}

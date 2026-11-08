package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiOwnerRegistryTest {

    @Test
    void unregisterDoesNotRemoveAReplacementGui() {
        var registry = new GuiOwnerRegistry<String>();
        var owner = entity(UUID.randomUUID());
        registry.register(owner, "old");
        registry.register(owner, "new");

        registry.unregister(owner, "old");

        assertEquals("new", registry.find(owner));
    }

    @Test
    void findsBlockOwnerAndIgnoresUnsupportedOwners() {
        var registry = new GuiOwnerRegistry<String>();
        var owner = mock(Container.class);
        when(owner.getLocation()).thenReturn(new org.bukkit.Location(null, 1, 2, 3));
        registry.register(owner, "block-gui");

        assertEquals("block-gui", registry.find(owner));
        assertNull(registry.find(mock(InventoryHolder.class)));
    }

    private static EntityOwner entity(UUID id) {
        var entity = mock(EntityOwner.class);
        when(entity.getUniqueId()).thenReturn(id);
        return entity;
    }

    private interface EntityOwner extends Entity, InventoryHolder {}
}

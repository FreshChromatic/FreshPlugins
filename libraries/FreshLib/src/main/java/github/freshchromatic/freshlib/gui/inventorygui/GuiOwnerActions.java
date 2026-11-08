package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.inventory.InventoryHolder;

interface GuiOwnerActions {

    InventoryHolder getOwner();

    boolean hasRealOwner();

    void destroy();
}

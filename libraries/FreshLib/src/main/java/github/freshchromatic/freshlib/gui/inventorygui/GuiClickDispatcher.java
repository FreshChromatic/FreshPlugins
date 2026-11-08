package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.inventory.ItemStack;

final class GuiClickDispatcher {

    private GuiClickDispatcher() {
    }

    static GuiElement.Click dispatch(InventoryGui gui, InventoryInteractEvent event, ClickType clickType, int slot,
        ItemStack cursor, GuiElement element, GuiElement.Action outsideAction) {
        GuiElement.Action action;
        if (slot == -999) {
            action = outsideAction;
        } else if (element == null) {
            action = null;
        } else {
            action = element.getAction(event.getWhoClicked());
        }
        GuiElement.Click click = new GuiElement.Click(gui, slot, clickType, cursor, element, event);
        if (action == null || action.onClick(click)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.updateInventory();
            }
        }
        return action == null ? null : click;
    }
}

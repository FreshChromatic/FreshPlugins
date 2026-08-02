package github.freshchromatic.freshlib.gui.menu;

import github.freshchromatic.freshlib.gui.inventorygui.InventoryGui;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class Menu {
    protected final JavaPlugin plugin;
    protected final String menuKey;
    protected final InventoryGui gui;

    public Menu(JavaPlugin plugin, MenuConfig menuConfig, String menuKey) {
        this(plugin, menuConfig, menuKey, menuConfig.getLayout(menuKey));
    }

    /**
     * Creates a menu with an explicit layout, allowing callers to provide a safe fallback when
     * their menu configuration is optional or user-editable.
     */
    public Menu(JavaPlugin plugin, MenuConfig menuConfig, String menuKey, String[] layout) {
        this.plugin = plugin;
        this.menuKey = menuKey;

        this.gui = new InventoryGui(plugin, menuConfig.getTitleRaw(menuKey), layout);
    }

    protected abstract void buildMenu();

    public void show(Player player) {
        buildMenu();
        gui.show(player);
    }

    public void destroy() {
        gui.destroy();
    }

    public InventoryGui getGui() {
        return gui;
    }
}

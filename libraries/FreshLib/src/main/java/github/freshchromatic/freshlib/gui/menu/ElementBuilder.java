package github.freshchromatic.freshlib.gui.menu;

import github.freshchromatic.freshlib.gui.inventorygui.GuiElement;
import org.bukkit.configuration.ConfigurationSection;

@FunctionalInterface
public interface ElementBuilder {
    /**
     * Builds a custom GUI element.
     *
     * @param character The character key in the layout mapping.
     * @param section   The configuration section corresponding to this item.
     * @return The built GuiElement.
     */
    GuiElement build(char character, ConfigurationSection section);
}

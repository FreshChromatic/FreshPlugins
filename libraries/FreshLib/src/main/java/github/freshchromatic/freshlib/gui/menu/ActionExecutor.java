package github.freshchromatic.freshlib.gui.menu;

import github.freshchromatic.freshlib.gui.inventorygui.GuiElement;

@FunctionalInterface
public interface ActionExecutor {
    /**
     * Executes an action triggered by a GUI click.
     *
     * @param click    The click event from the GUI element.
     * @param argument The argument string after the executor prefix (may be empty).
     */
    void execute(GuiElement.Click click, String argument);
}

package github.freshchromatic.freshlib.item;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves a namespaced material tag (e.g. {@code "[itemsadder] my_item_id"}) from a YAML
 * {@code material} field into a base {@link ItemStack}. Mirrors CommandPanels' material-component
 * pattern. Register custom resolvers via {@link github.freshchromatic.freshlib.gui.menu.MenuItemBuilder#registerMaterialResolver}.
 */
public interface MaterialResolver {

    /**
     * @param materialTag The raw, untrimmed material string as written in YAML
     *                     (e.g. {@code "[nexo] my_id"} or {@code "head:Notch"}).
     * @return Whether this resolver should handle the given tag. Implementations are responsible
     *         for stripping their own prefix/marker out of the tag in {@link #resolve}.
     */
    boolean supports(String materialTag);

    /**
     * @param materialTag The same raw tag passed to {@link #supports}.
     * @param player      The player the item is being built for; may be null.
     * @return The resolved item, or null if it could not be resolved (caller should fall back to BARRIER).
     */
    ItemStack resolve(String materialTag, Player player);
}

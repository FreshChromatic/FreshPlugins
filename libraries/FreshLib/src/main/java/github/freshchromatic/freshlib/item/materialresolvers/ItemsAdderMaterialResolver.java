package github.freshchromatic.freshlib.item.materialresolvers;

import dev.lone.itemsadder.api.CustomStack;
import github.freshchromatic.freshlib.item.MaterialResolver;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** {@code [itemsadder] my_item_id} — resolves an item registered with the ItemsAdder plugin. */
public final class ItemsAdderMaterialResolver implements MaterialResolver {

    @Override
    public boolean supports(String materialTag) {
        return materialTag.trim().toLowerCase(Locale.ROOT).startsWith("[itemsadder]");
    }

    @Override
    public ItemStack resolve(String materialTag, Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            Logging.logger().warning("Material tag \"" + materialTag + "\" requires the ItemsAdder plugin, which isn't installed.");
            return new ItemStack(Material.BARRIER);
        }
        String id = materialTag.trim().substring("[itemsadder]".length()).trim();
        CustomStack stack = CustomStack.getInstance(id);
        if (stack == null) {
            Logging.logger().warning("Unknown ItemsAdder item id: \"" + id + "\"");
            return new ItemStack(Material.BARRIER);
        }
        return stack.getItemStack().clone();
    }
}

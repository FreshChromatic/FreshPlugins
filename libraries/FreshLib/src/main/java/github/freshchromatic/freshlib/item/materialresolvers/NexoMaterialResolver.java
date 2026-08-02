package github.freshchromatic.freshlib.item.materialresolvers;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import github.freshchromatic.freshlib.item.MaterialResolver;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** {@code [nexo] my_item_id} — resolves an item registered with the Nexo plugin. */
public final class NexoMaterialResolver implements MaterialResolver {

    @Override
    public boolean supports(String materialTag) {
        return materialTag.trim().toLowerCase(Locale.ROOT).startsWith("[nexo]");
    }

    @Override
    public ItemStack resolve(String materialTag, Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            Logging.logger().warning("Material tag \"" + materialTag + "\" requires the Nexo plugin, which isn't installed.");
            return new ItemStack(Material.BARRIER);
        }
        String id = materialTag.trim().substring("[nexo]".length()).trim();
        ItemBuilder builder = NexoItems.itemFromId(id);
        if (builder == null) {
            Logging.logger().warning("Unknown Nexo item id: \"" + id + "\"");
            return new ItemStack(Material.BARRIER);
        }
        return builder.build();
    }
}

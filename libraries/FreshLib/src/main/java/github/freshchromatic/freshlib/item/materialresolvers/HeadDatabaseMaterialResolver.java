package github.freshchromatic.freshlib.item.materialresolvers;

import github.freshchromatic.freshlib.item.MaterialResolver;
import github.freshchromatic.freshlib.util.Logging;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** {@code [hdb] my_head_id} — resolves a head registered with the HeadDatabase plugin. */
public final class HeadDatabaseMaterialResolver implements MaterialResolver {

    @Override
    public boolean supports(String materialTag) {
        return materialTag.trim().toLowerCase(Locale.ROOT).startsWith("[hdb]");
    }

    @Override
    public ItemStack resolve(String materialTag, Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) {
            Logging.logger().warning("Material tag \"" + materialTag + "\" requires the HeadDatabase plugin, which isn't installed.");
            return new ItemStack(Material.BARRIER);
        }
        String id = materialTag.trim().substring("[hdb]".length()).trim();
        // Constructed lazily here (not as a field) — HeadDatabaseAPI must only be referenced
        // after confirming the plugin is enabled, otherwise this throws even when unused.
        ItemStack head = new HeadDatabaseAPI().getItemHead(id);
        if (head == null) {
            Logging.logger().warning("Unknown HeadDatabase head id: \"" + id + "\"");
            return new ItemStack(Material.BARRIER);
        }
        return head;
    }
}

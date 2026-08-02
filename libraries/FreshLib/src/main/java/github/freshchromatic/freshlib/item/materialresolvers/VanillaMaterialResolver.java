package github.freshchromatic.freshlib.item.materialresolvers;

import github.freshchromatic.freshlib.item.MaterialResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * {@code [mc] STONE} or {@code [mc] STONE[damage=5]} — a plain vanilla {@link Material}, optionally
 * followed by an item-component string applied via {@link org.bukkit.UnsafeValues#modifyItemStack}.
 */
public final class VanillaMaterialResolver implements MaterialResolver {

    @Override
    public boolean supports(String materialTag) {
        return materialTag.toLowerCase(Locale.ROOT).trim().startsWith("[mc]");
    }

    @Override
    public ItemStack resolve(String materialTag, Player player) {
        String body = materialTag.trim().substring("[mc]".length()).trim();
        if (body.isEmpty()) return null;

        String materialId = body;
        String components = null;
        int bracketIndex = body.indexOf('[');
        if (bracketIndex != -1) {
            materialId = body.substring(0, bracketIndex);
            components = body.substring(bracketIndex);
        }

        Material material = Material.matchMaterial(materialId.toUpperCase(Locale.ROOT));
        if (material == null) return null;

        ItemStack stack = new ItemStack(material);
        if (components != null) {
            stack = Bukkit.getUnsafe().modifyItemStack(stack, components);
        }
        return stack;
    }
}

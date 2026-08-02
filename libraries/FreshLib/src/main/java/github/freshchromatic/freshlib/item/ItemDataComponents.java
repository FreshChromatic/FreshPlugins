package github.freshchromatic.freshlib.item;

import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Applies the Paper {@link ItemMeta}/{@link Damageable} setters that CommandPanels exposes as
 * Custom Model Data / Item Model / Item Tooltip Style / Item Tooltip / Item Damage. All methods
 * are no-ops on null/blank input so callers can apply them unconditionally from optional YAML fields.
 */
public final class ItemDataComponents {

    private ItemDataComponents() {
    }

    public static void applyCustomModelData(ItemMeta meta, String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            meta.setCustomModelData(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Logging.logger().warning("Invalid custom-model-data value: \"" + raw + "\"");
        }
    }

    public static void applyItemModel(ItemMeta meta, String namespacedKey) {
        NamespacedKey key = parseKey(namespacedKey, "item-model");
        if (key != null) {
            meta.setItemModel(key);
        }
    }

    public static void applyTooltipStyle(ItemMeta meta, String namespacedKey) {
        NamespacedKey key = parseKey(namespacedKey, "tooltip-style");
        if (key != null) {
            meta.setTooltipStyle(key);
        }
    }

    public static void applyHideTooltip(ItemMeta meta, Boolean hide) {
        if (hide == null) return;
        meta.setHideTooltip(hide);
    }

    public static void applyDamage(ItemMeta meta, Integer damage) {
        if (damage == null) return;
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damage);
        }
    }

    private static NamespacedKey parseKey(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) return null;
        NamespacedKey key = NamespacedKey.fromString(raw.trim());
        if (key == null) {
            Logging.logger().warning("Invalid " + fieldName + " namespaced key: \"" + raw + "\"");
        }
        return key;
    }
}

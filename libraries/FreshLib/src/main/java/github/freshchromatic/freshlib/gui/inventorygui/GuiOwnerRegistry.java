package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class GuiOwnerRegistry<T> {

    private final Map<String, T> owners = new ConcurrentHashMap<>();

    void register(InventoryHolder owner, T gui) {
        String key = key(owner);
        if (key != null) {
            owners.put(key, gui);
        }
    }

    void unregister(InventoryHolder owner, T gui) {
        String key = key(owner);
        if (key != null) {
            owners.remove(key, gui);
        }
    }

    T find(InventoryHolder owner) {
        String key = key(owner);
        return key == null ? null : owners.get(key);
    }

    private static String key(InventoryHolder owner) {
        if (owner instanceof Entity entity) {
            return entity.getUniqueId().toString();
        }
        if (owner instanceof BlockState blockState) {
            return blockState.getLocation().toString();
        }
        return null;
    }
}

package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GuiViewerRegistry {

    enum Removal {
        NOT_REGISTERED, REMAINING_VIEWERS, LAST_VIEWER
    }

    private final Map<UUID, Inventory> inventories = new ConcurrentHashMap<>();

    Inventory get(UUID viewerId) {
        return inventories.get(viewerId);
    }

    synchronized void put(UUID viewerId, Inventory inventory) {
        inventories.put(viewerId, inventory);
    }

    List<UUID> viewerIds() {
        return List.copyOf(inventories.keySet());
    }

    List<Inventory> values() {
        return List.copyOf(inventories.values());
    }

    boolean isEmpty() {
        return inventories.isEmpty();
    }

    synchronized Removal remove(UUID viewerId, Inventory inventory) {
        if (!inventories.remove(viewerId, inventory)) {
            return Removal.NOT_REGISTERED;
        }
        return inventories.isEmpty() ? Removal.LAST_VIEWER : Removal.REMAINING_VIEWERS;
    }

    synchronized List<Map.Entry<UUID, Inventory>> clear() {
        List<Map.Entry<UUID, Inventory>> removed = new ArrayList<>(inventories.entrySet());
        inventories.clear();
        return removed;
    }
}

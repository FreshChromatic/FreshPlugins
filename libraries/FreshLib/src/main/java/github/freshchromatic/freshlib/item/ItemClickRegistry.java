package github.freshchromatic.freshlib.item;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ItemClickRegistry {

    private static final Map<String, ItemClick> itemClickMap = new ConcurrentHashMap<>();

    public static ItemClick getItemClick(String id) {
        return id == null ? ItemClick.EMPTY : itemClickMap.getOrDefault(id, ItemClick.EMPTY);
    }

    public static void registerItemClick(ItemClick itemClick) {
        Objects.requireNonNull(itemClick);
        itemClickMap.put(Objects.requireNonNull(itemClick.getId()), itemClick);
    }

}

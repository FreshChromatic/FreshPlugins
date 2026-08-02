package github.freshchromatic.freshlib.item;

import github.freshchromatic.freshlib.FreshLib;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

public interface ItemClick {
    NamespacedKey ON_CLICK_KEY = new NamespacedKey(FreshLib.getInstance().getPlugin(), "oninteract");

    ItemClick EMPTY = new ItemClick() {
        @Override
        public String getId() {
            return null;
        }

        @Override
        public void onClick(PlayerInteractEvent event, Player player) {
        }
    };

    String getId();

    void onClick(PlayerInteractEvent event, Player player);

    default void register() {
        ItemClickRegistry.registerItemClick(this);
    }

}

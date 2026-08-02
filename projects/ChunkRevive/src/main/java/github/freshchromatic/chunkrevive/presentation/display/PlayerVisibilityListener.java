package github.freshchromatic.chunkrevive.presentation.display;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks chunk changes for marker visibility and eye-height updates. */
public final class PlayerVisibilityListener implements Listener {
    private final ChunkDisplayService displayService;
    private final Map<UUID, long[]> lastChunk = new ConcurrentHashMap<>();

    public PlayerVisibilityListener(ChunkDisplayService displayService) {
        this.displayService = displayService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var player = event.getPlayer();
        var to = event.getTo();
        displayService.updateCachedEyeY(player.getUniqueId(), to.getY() + player.getEyeHeight());

        int cx = to.getBlockX() >> 4;
        int cz = to.getBlockZ() >> 4;
        long[] previous = lastChunk.put(player.getUniqueId(), new long[]{cx, cz});
        if (previous == null || previous[0] != cx || previous[1] != cz) {
            displayService.refreshVisibility(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastChunk.remove(event.getPlayer().getUniqueId());
    }
}

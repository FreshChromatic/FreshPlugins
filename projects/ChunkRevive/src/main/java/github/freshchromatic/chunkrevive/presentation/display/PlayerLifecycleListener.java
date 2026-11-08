package github.freshchromatic.chunkrevive.presentation.display;

import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Owns player-scoped display and follow-mode lifecycle cleanup. */
public final class PlayerLifecycleListener implements Listener {

    private final Plugin              plugin;
    private final MarkService         markService;
    private final ChunkDisplayService displayService;

    public PlayerLifecycleListener(Plugin plugin, MarkService markService, ChunkDisplayService displayService) {
        this.plugin = plugin;
        this.markService = markService;
        this.displayService = displayService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        Scheduler.runTaskLater(plugin, () -> displayService.spawnDisplaysForPlayer(player), 1L, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        displayService.removeDisplaysForPlayer(playerId);
        markService.clearFollowMode(playerId);
    }
}

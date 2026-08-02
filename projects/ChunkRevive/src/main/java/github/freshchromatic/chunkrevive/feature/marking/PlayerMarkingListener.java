package github.freshchromatic.chunkrevive.feature.marking;

import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PlayerMarkingListener implements Listener {

    private final MarkService markService;
    private final Supplier<Messages> messages;
    private final Supplier<PluginConfig> config;
    private final StructureService structureService;
    private final Plugin plugin;

    // Last known chunk coords per player for change detection
    private final Map<UUID, long[]> lastChunk = new ConcurrentHashMap<>();

    public PlayerMarkingListener(MarkService markService,
                                Supplier<Messages> messages,
                                Supplier<PluginConfig> config, StructureService structureService,
                                Plugin plugin) {
        this.markService = markService;
        this.messages = messages;
        this.config = config;
        this.structureService = structureService;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        var to = event.getTo();

        int newCX = to.getBlockX() >> 4;
        int newCZ = to.getBlockZ() >> 4;

        long[] last = lastChunk.get(player.getUniqueId());
        if (last != null && last[0] == newCX && last[1] == newCZ) return;
        lastChunk.put(player.getUniqueId(), new long[]{newCX, newCZ});

        String world = to.getWorld().getName();

        // Follow mode processing
        markService.followMode(player.getUniqueId()).ifPresent(mode -> {
            switch (mode) {
                case MARK -> {
                    var result = markService.mark(world, newCX, newCZ, player.getUniqueId());
                    if (result == MarkService.MarkStatus.CLAIM_BLOCKED) {
                        var mc = new github.freshchromatic.chunkrevive.feature.marking.MarkedChunk(world, newCX, newCZ, player.getUniqueId(), 0);
                        player.sendMessage(messages.get().mark.residenceBlocked.withPlaceholders(
                            Components.placeholder("cx_cz", mc.coordDisplay())));
                    }
                }
                case UNMARK -> {
                    if (markService.isMarked(world, newCX, newCZ)) {
                        boolean removed = markService.unmark(world, newCX, newCZ, player.getUniqueId());
                        if (removed) {
                            var mc = new github.freshchromatic.chunkrevive.feature.marking.MarkedChunk(world, newCX, newCZ, player.getUniqueId(), 0);
                            player.sendMessage(messages.get().unmark.success.withPlaceholders(
                                Components.placeholder("cx_cz", mc.coordDisplay())));
                        }
                    }
                }
            }
        });

        detectStructures(player, to.getWorld().getName(), to.getBlockX(), to.getBlockZ());
    }

    /** Detect once after joining so standing still does not postpone detection until a chunk change. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduler.runTaskLater(
            plugin, () -> detectStructuresAtCurrentLocation(player), 1L, player);
    }

    public void detectStructuresAtCurrentLocation(Player player) {
        if (!player.isOnline()) return;
        var location = player.getLocation();
        detectStructures(player, location.getWorld().getName(),
            location.getBlockX(), location.getBlockZ());
    }

    private void detectStructures(Player player, String world, int blockX, int blockZ) {
        // Structure auto-detection: register/mark any tracked structure at this location.
        var currentConfig = config.get();
        if (currentConfig.structure.enabled && currentConfig.structure.detect.autoDetectOnWalk
            && structureService.canAutoDetect(world)) {
            for (var detection : structureService.detectAndRegister(
                    player.getWorld(), blockX, blockZ, player.getUniqueId())) {
                if (currentConfig.structure.detect.notifyPlayerOnDetect
                        && detection.newGroup() && detection.newlyMarked() > 0) {
                    player.sendMessage(messages.get().structure.detected.withPlaceholders(
                        Components.placeholder("structure_name", StructureRegistry.displayName(detection.structureId())),
                        Components.placeholder("count", String.valueOf(detection.newlyMarked()))));
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastChunk.remove(event.getPlayer().getUniqueId());
    }
}

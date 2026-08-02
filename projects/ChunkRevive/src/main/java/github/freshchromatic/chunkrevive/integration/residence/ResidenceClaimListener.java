package github.freshchromatic.chunkrevive.integration.residence;

import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceSubzoneCreationEvent;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import java.util.function.Consumer;

/** Removes existing ChunkRevive marks once a new Residence claim becomes effective. */
public final class ResidenceClaimListener implements Listener {

    private final Plugin plugin;
    private final MarkService markService;
    private final ClaimNotifier notifier;

    @FunctionalInterface
    public interface ClaimNotifier {
        void notify(String world, int minCx, int maxCx, int minCz, int maxCz);
    }

    public ResidenceClaimListener(Plugin plugin, MarkService markService, ClaimNotifier notifier) {
        this.plugin = plugin;
        this.markService = markService;
        this.notifier = notifier;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceCreated(ResidenceCreationEvent event) {
        queueUnmark(event.getPhysicalArea());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceAreaAdded(ResidenceAreaAddEvent event) {
        queueUnmark(event.getPhysicalArea());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceSizeChanged(ResidenceSizeChangeEvent event) {
        queueUnmark(event.getNewArea());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSubzoneCreated(ResidenceSubzoneCreationEvent event) {
        queueUnmark(event.getPhysicalArea());
    }

    private void queueUnmark(CuboidArea area) {
        if (area == null || area.getWorldName() == null
            || area.getLowVector() == null || area.getHighVector() == null) return;

        String world = area.getWorldName();
        int minCx = area.getLowVector().getBlockX() >> 4;
        int maxCx = area.getHighVector().getBlockX() >> 4;
        int minCz = area.getLowVector().getBlockZ() >> 4;
        int maxCz = area.getHighVector().getBlockZ() >> 4;

        // Residence fires these cancellable events immediately before committing its indexes.
        // Defer one tick so marks disappear only after the claim has actually become effective.
        Scheduler.runTask(plugin, () -> {
            int removed = markService.unmarkArea(world, minCx, maxCx, minCz, maxCz);
            if (removed > 0) {
                Logging.logger().info("Removed " + removed + " pending ChunkRevive mark(s) covered by a new Residence in "
                    + world + " [" + minCx + "," + minCz + " -> " + maxCx + "," + maxCz + "].");
            }
            notifier.notify(world, minCx, maxCx, minCz, maxCz);
        });
    }
}

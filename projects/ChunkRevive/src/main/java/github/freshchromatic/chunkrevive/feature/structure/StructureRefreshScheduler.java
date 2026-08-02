package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Periodically refreshes structure groups whose refresh period has elapsed. */
public final class StructureRefreshScheduler {

    private final Plugin plugin;
    private final StructureRegistry structureRegistry;
    private final MarkRegistry markRegistry;
    private final WorldAccessPolicy worldAccessPolicy;
    private PluginConfig config;
    private Messages messages;

    private ScheduledTask task;

    public StructureRefreshScheduler(Plugin plugin, PluginConfig config, Messages messages,
                                      StructureRegistry structureRegistry, MarkRegistry markRegistry,
                                      WorldAccessPolicy worldAccessPolicy) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.structureRegistry = structureRegistry;
        this.markRegistry = markRegistry;
        this.worldAccessPolicy = worldAccessPolicy;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void start() {
        stop();
        if (!config.structure.enabled || !config.structure.refresh.enabled) return;
        long interval = Math.max(20L, config.structure.refresh.checkIntervalTicks);
        task = Scheduler.runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!config.structure.enabled || !config.structure.refresh.enabled) return;
        if (markRegistry.getRegenerationQueue().isRunning()) return;
        long now = System.currentTimeMillis();
        long intervalTicks = Math.max(20L, config.structure.refresh.checkIntervalTicks);

        List<MarkedChunk> dueChunks = new ArrayList<>();
        for (StructureGroup group : List.copyOf(structureRegistry.getAllGroups())) {
            if (!config.structure.refresh.isTracked(group.structureId())) continue;
            if (group.nextRefreshAt() <= 0 || group.nextRefreshAt() > now) continue;

            if (!worldAccessPolicy.isAllowed(group.world(), WorldAccessPolicy.Scope.STRUCTURE_REFRESH)) {
                if (config.enableDebugLogs) {
                    Bukkit.getLogger().info("[ChunkRevive] Skipping due refresh for " + group.structureId()
                        + " — world " + group.world() + " is disallowed for structure-refresh.");
                }
                continue;
            }

            if (group.blocked()) {
                adminBroadcastAudience().sendMessage(messages.structure.refreshBlocked.withPlaceholders(
                    Components.placeholder("structure_name", StructureRegistry.displayName(group.structureId()))));
                structureRegistry.postponeRefresh(group.groupId(), intervalTicks);
                continue;
            }

            List<int[]> effective = structureRegistry.resolveEffectiveChunks(group.world(),
                group.minChunkX(), group.maxChunkX(), group.minChunkZ(), group.maxChunkZ());
            if (effective == null || effective.isEmpty()) {
                structureRegistry.postponeRefresh(group.groupId(), intervalTicks);
                continue;
            }

            List<MarkedChunk> groupChunks = new ArrayList<>();
            for (int[] coord : effective) {
                groupChunks.add(new MarkedChunk(group.world(), coord[0], coord[1], group.groupId(), now, group.groupId()));
            }
            dueChunks.addAll(groupChunks);

            adminBroadcastAudience().sendMessage(messages.structure.refreshStart.withPlaceholders(
                Components.placeholder("structure_name", StructureRegistry.displayName(group.structureId())),
                Components.placeholder("count", String.valueOf(groupChunks.size()))));
        }

        if (dueChunks.isEmpty()) return;
        markRegistry.markChunksDirect(dueChunks);
        markRegistry.getRegenerationQueue().start(dueChunks, adminBroadcastAudience(),
            batch -> markRegistry.onChunksRegenComplete(batch));
    }

    private Audience adminBroadcastAudience() {
        List<Audience> admins = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("chunkrevive.admin")) admins.add(p);
        }
        admins.add(Bukkit.getConsoleSender());
        return Audience.audience(admins);
    }
}

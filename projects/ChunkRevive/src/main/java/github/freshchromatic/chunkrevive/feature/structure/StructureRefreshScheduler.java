package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Periodically refreshes structure groups whose refresh period has elapsed. */
public final class StructureRefreshScheduler {

    private final Plugin                plugin;
    private final StructureRegistry     structureRegistry;
    private final MarkRegistry          markRegistry;
    private final WorldAccessPolicy     worldAccessPolicy;
    private final ProtectedMarkExecutor protectedMarks;

    private PluginConfig config;
    private Messages     messages;

    private ScheduledTask    task;
    private volatile boolean checking;
    private final AtomicLong generation = new AtomicLong();

    public StructureRefreshScheduler(Plugin plugin, PluginConfig config, Messages messages,
        StructureRegistry structureRegistry, MarkRegistry markRegistry, WorldAccessPolicy worldAccessPolicy,
        ProtectedMarkExecutor protectedMarks) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.structureRegistry = structureRegistry;
        this.markRegistry = markRegistry;
        this.worldAccessPolicy = worldAccessPolicy;
        this.protectedMarks = protectedMarks;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void start() {
        stop();
        if (!config.structure.enabled || !config.structure.refresh.enabled) {
            return;
        }
        long interval = Math.max(20L, config.structure.refresh.checkIntervalTicks);
        long scheduledGeneration = generation.get();
        task = Scheduler.runTaskTimer(plugin, () -> tick(scheduledGeneration), interval, interval);
    }

    public void stop() {
        generation.incrementAndGet();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick(long scheduledGeneration) {
        if (scheduledGeneration != generation.get()) {
            return;
        }
        if (!config.structure.enabled || !config.structure.refresh.enabled) {
            return;
        }
        if (checking || markRegistry.getRegenerationQueue().isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        long intervalTicks = Math.max(20L, config.structure.refresh.checkIntervalTicks);

        List<MarkedChunk> dueChunks = new ArrayList<>();
        for (StructureGroup group : List.copyOf(structureRegistry.getAllGroups())) {
            if (!config.structure.refresh.isTracked(group.structureId())) {
                continue;
            }
            if (group.nextRefreshAt() <= 0 || group.nextRefreshAt() > now) {
                continue;
            }

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
                postponeRefresh(group.groupId(), intervalTicks);
                continue;
            }

            List<ChunkCoordinate> effective = structureRegistry.resolveEffectiveChunks(group.world(), group.minChunkX(),
                group.maxChunkX(), group.minChunkZ(), group.maxChunkZ());
            if (effective == null || effective.isEmpty()) {
                postponeRefresh(group.groupId(), intervalTicks);
                continue;
            }

            List<MarkedChunk> groupChunks = new ArrayList<>();
            for (ChunkCoordinate coord : effective) {
                groupChunks.add(new MarkedChunk(group.world(), coord.x(), coord.z(), group.groupId(), now,
                    group.groupId()));
            }
            dueChunks.addAll(groupChunks);
        }

        if (dueChunks.isEmpty()) {
            return;
        }
        checking = true;
        protectedMarks.markBatch(dueChunks).whenComplete((outcome, failure) -> {
            checking = false;
            if (scheduledGeneration != generation.get()) {
                return;
            }
            if (failure != null) {
                plugin.getLogger().warning("Structure refresh protection check failed: " + failure.getMessage());
                return;
            }
            if (task == null || !config.structure.enabled || !config.structure.refresh.enabled
                || outcome.eligible().isEmpty() || markRegistry.getRegenerationQueue().isRunning()) {
                return;
            }
            List<StructureGroup> refreshing = List.copyOf(structureRegistry.getAllGroups())
                .stream()
                .filter(
                group ->
                    outcome.eligible()
                        .stream()
                        .anyMatch(chunk -> group.groupId().equals(chunk.structureGroupId())))
                .toList();
            Audience audience = adminBroadcastAudience();
            boolean started = markRegistry.getRegenerationQueue().start(outcome.eligible(), audience,
                batch -> markRegistry.onChunksRegenComplete(batch, task -> Bukkit.getGlobalRegionScheduler().execute(
                    plugin, task)).whenComplete((ignored, persistenceFailure) -> {
                        if (persistenceFailure != null) {
                            plugin.getLogger().warning("Failed to persist structure or mark state after regeneration: "
                                + persistenceFailure.getMessage());
                        }
                    }));
            if (!started) {
                refreshing.forEach(group -> postponeRefresh(group.groupId(), intervalTicks));
                return;
            }
            for (StructureGroup group : refreshing) {
                long count = outcome.eligible()
                    .stream()
                    .filter(chunk -> group.groupId().equals(chunk.structureGroupId()))
                    .count();
                audience.sendMessage(messages.structure.refreshStart.withPlaceholders(Components.placeholder(
                    "structure_name", StructureRegistry.displayName(group.structureId())), Components.placeholder(
                        "count", String.valueOf(count))));
            }
        });
    }

    private Audience adminBroadcastAudience() {
        List<Audience> admins = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("chunkrevive.admin")) {
                admins.add(p);
            }
        }
        admins.add(Bukkit.getConsoleSender());
        return Audience.audience(admins);
    }

    private void postponeRefresh(UUID groupId, long intervalTicks) {
        structureRegistry.postponeRefresh(groupId, intervalTicks).exceptionally(failure -> {
            plugin.getLogger().warning("Failed to postpone structure refresh: " + failure.getMessage());
            return null;
        });
    }
}

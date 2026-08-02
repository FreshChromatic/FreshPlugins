package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Passive replacement for /keep's old instant-unmark: accumulates per-structure protection time while players linger nearby. */
public final class StructureProtectionTracker {

    private final Plugin plugin;
    private final MarkRegistry markRegistry;
    private final StructureRegistry structureRegistry;
    private PluginConfig config;
    private Messages messages;

    private ScheduledTask task;

    public StructureProtectionTracker(Plugin plugin, PluginConfig config, Messages messages,
                              MarkRegistry markRegistry, StructureRegistry structureRegistry) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void start() {
        stop();
        if (!config.structure.enabled) return;
        task = Scheduler.runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!config.structure.enabled) return;
        int radius = config.structure.protection.radiusChunks;

        Set<UUID> activeGroups = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int pcx = p.getLocation().getBlockX() >> 4;
            int pcz = p.getLocation().getBlockZ() >> 4;
            String world = p.getWorld().getName();
            for (var chunk : markRegistry.getMarkedChunksNear(world, pcx, pcz, radius)) {
                if (chunk.structureGroupId() != null) activeGroups.add(chunk.structureGroupId());
            }
        }

        for (StructureGroup group : structureRegistry.getAllGroups()) {
            if (!config.structure.refresh.isTracked(group.structureId())) continue;
            if (group.blocked()) continue;
            boolean active = activeGroups.contains(group.groupId());
            if (active) {
                long before = group.protectionTicks();
                structureRegistry.addProtectionTicks(group.groupId(), 20L);
                boolean nowBlocked = structureRegistry.getGroup(group.groupId()).map(StructureGroup::blocked).orElse(false);
                if (nowBlocked && before < config.structure.protection.requiredTicks) {
                    Bukkit.broadcast(messages.structure.protectionEntered.withPlaceholders(
                        Components.placeholder("structure_name", StructureRegistry.displayName(group.structureId()))));
                }
            } else if (config.structure.protection.resetOnLeave) {
                structureRegistry.resetProtectionTicks(group.groupId());
            }
        }
    }

    /** Used by the repurposed /keep command to report nearby structures' protection progress. */
    public java.util.List<StructureGroup> findNearbyTrackedGroups(org.bukkit.Location location, int radiusChunks) {
        String world = location.getWorld().getName();
        int pcx = location.getBlockX() >> 4;
        int pcz = location.getBlockZ() >> 4;

        java.util.List<StructureGroup> result = new java.util.ArrayList<>();
        for (var chunk : markRegistry.getMarkedChunksNear(world, pcx, pcz, radiusChunks)) {
            if (chunk.structureGroupId() == null) continue;
            structureRegistry.getGroup(chunk.structureGroupId()).ifPresent(g -> {
                if (config.structure.refresh.isTracked(g.structureId()) && !result.contains(g)) {
                    result.add(g);
                }
            });
        }
        return result;
    }
}

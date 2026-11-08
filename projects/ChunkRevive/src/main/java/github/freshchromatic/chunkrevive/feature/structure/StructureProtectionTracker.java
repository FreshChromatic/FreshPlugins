package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Passive replacement for /keep's old instant-unmark: accumulates per-structure protection time while players linger nearby. */
public final class StructureProtectionTracker {

    private static final Logger LOGGER = Logger.getLogger(StructureProtectionTracker.class.getName());

    private final Plugin            plugin;
    private final MarkRegistry      markRegistry;
    private final StructureRegistry structureRegistry;

    private PluginConfig config;
    private Messages     messages;

    private ScheduledTask       task;
    private final AtomicBoolean scanInProgress = new AtomicBoolean();
    private final AtomicLong    generation     = new AtomicLong();

    public StructureProtectionTracker(Plugin plugin, PluginConfig config, Messages messages, MarkRegistry markRegistry,
        StructureRegistry structureRegistry) {
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
        if (!config.structure.enabled) {
            return;
        }
        task = Scheduler.runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        generation.incrementAndGet();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!config.structure.enabled || !scanInProgress.compareAndSet(false, true)) {
            return;
        }
        long scanGeneration = generation.get();
        try {
            List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
            StructureProtectionTick scan = new StructureProtectionTick(players.size(),
                result -> scheduleProtectionUpdate(scanGeneration, result));
            int radius = config.structure.protection.radiusChunks;
            for (Player player : players) {
                try {
                    Scheduler.runTask(plugin, () -> {
                        Set<UUID> activeGroups = new HashSet<>();
                        try {
                            int playerChunkX = player.getLocation().getBlockX() >> 4;
                            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
                            String world = player.getWorld().getName();
                            for (var chunk : markRegistry.getMarkedChunksNear(world, playerChunkX, playerChunkZ,
                                radius)) {
                                if (chunk.structureGroupId() != null) {
                                    activeGroups.add(chunk.structureGroupId());
                                }
                            }
                        } catch (RuntimeException failure) {
                            LOGGER.log(Level.WARNING, "Unable to inspect a player for structure protection", failure);
                            scan.playerFailed(activeGroups);
                            return;
                        } catch (Error failure) {
                            scan.playerFailed(activeGroups);
                            throw failure;
                        }
                        scan.playerCompleted(activeGroups);
                    }, () -> scan.playerFailed(Set.of()), player);
                } catch (RuntimeException failure) {
                    LOGGER.log(Level.WARNING, "Unable to schedule a structure protection scan", failure);
                    scan.playerFailed(Set.of());
                } catch (Error failure) {
                    scan.playerFailed(Set.of());
                    throw failure;
                }
            }
        } catch (RuntimeException failure) {
            scanInProgress.set(false);
            LOGGER.log(Level.SEVERE, "Unable to start structure protection scan", failure);
        }
    }

    private void scheduleProtectionUpdate(long scanGeneration, StructureProtectionTick.Result result) {
        try {
            Scheduler.runTask(plugin, () -> applyProtection(scanGeneration, result));
        } catch (RuntimeException failure) {
            scanInProgress.set(false);
            LOGGER.log(Level.WARNING, "Unable to schedule structure protection update", failure);
        } catch (Error failure) {
            scanInProgress.set(false);
            throw failure;
        }
    }

    private void applyProtection(long scanGeneration, StructureProtectionTick.Result result) {
        try {
            if (generation.get() != scanGeneration || !config.structure.enabled) {
                return;
            }
            for (StructureGroup group : structureRegistry.getAllGroups()) {
                if (!config.structure.refresh.isTracked(group.structureId())) {
                    continue;
                }
                if (group.blocked()) {
                    continue;
                }
                boolean active = result.activeGroupIds().contains(group.groupId());
                if (active) {
                    long before = group.protectionTicks();
                    structureRegistry.addProtectionTicks(group.groupId(), 20L);
                    boolean nowBlocked = structureRegistry.getGroup(group.groupId())
                        .map(StructureGroup::blocked)
                        .orElse(false);
                    if (nowBlocked && before < config.structure.protection.requiredTicks) {
                        Bukkit.broadcast(messages.structure.protectionEntered.withPlaceholders(Components.placeholder(
                            "structure_name", StructureRegistry.displayName(group.structureId()))));
                    }
                } else if (result.complete() && config.structure.protection.resetOnLeave) {
                    structureRegistry.resetProtectionTicks(group.groupId());
                }
            }
        } finally {
            scanInProgress.set(false);
        }
    }

    /** Used by the repurposed /keep command to report nearby structures' protection progress. */
    public List<StructureGroup> findNearbyTrackedGroups(Location location, int radiusChunks) {
        String world = location.getWorld().getName();
        int pcx = location.getBlockX() >> 4;
        int pcz = location.getBlockZ() >> 4;

        List<StructureGroup> result = new ArrayList<>();
        for (var chunk : markRegistry.getMarkedChunksNear(world, pcx, pcz, radiusChunks)) {
            if (chunk.structureGroupId() == null) {
                continue;
            }
            structureRegistry.getGroup(chunk.structureGroupId()).ifPresent(g -> {
                if (config.structure.refresh.isTracked(g.structureId()) && !result.contains(g)) {
                    result.add(g);
                }
            });
        }
        return result;
    }
}

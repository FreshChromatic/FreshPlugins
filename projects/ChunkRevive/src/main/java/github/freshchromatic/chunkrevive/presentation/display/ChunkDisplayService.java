package github.freshchromatic.chunkrevive.presentation.display;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkDisplay;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.freshlib.util.Components;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import me.tofaa.entitylib.wrapper.WrapperEntity;
import net.kyori.adventure.text.Component;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkDisplayService implements MarkDisplay {

    private final Plugin plugin;
    private final MarkRegistry markRegistry;
    private final boolean packetEventsAvailable;
    private StructureRegistry structureRegistry;
    private PluginConfig config;
    private Messages messages;

    // playerUUID → (chunkKey → WrapperEntity)
    private final Map<UUID, Map<Long, WrapperEntity>> playerDisplays = new ConcurrentHashMap<>();
    // main-thread write (PlayerMoveEvent), async-task read
    private final Map<UUID, Double> cachedEyeY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastUpdatedY = new ConcurrentHashMap<>();

    private ScheduledTask updateTask;

    public ChunkDisplayService(Plugin plugin, MarkRegistry markRegistry,
                                PluginConfig config, Messages messages, boolean packetEventsAvailable) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.config = config;
        this.messages = messages;
        this.packetEventsAvailable = packetEventsAvailable;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void setStructureRegistry(StructureRegistry structureRegistry) {
        this.structureRegistry = structureRegistry;
    }

    public void start() {
        if (!isEnabled()) return;
        updateTask = Scheduler.runTaskTimerAsynchronously(
            plugin, this::tickYUpdate, 0L, config.display.updateIntervalTicks);
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
        playerDisplays.values().forEach(map -> map.values().forEach(WrapperEntity::remove));
        playerDisplays.clear();
        cachedEyeY.clear();
        lastUpdatedY.clear();
    }

    /** Called on main thread (PlayerMoveEvent) — async task reads this value. */
    public void updateCachedEyeY(UUID playerId, double eyeY) {
        if (!isEnabled()) return;
        cachedEyeY.put(playerId, eyeY);
    }

    /** Called 1 tick after PlayerJoinEvent. Must be called on main thread. */
    public void spawnDisplaysForPlayer(Player player) {
        if (!isEnabled()) return;
        double eyeY = player.getEyeLocation().getY();
        UUID uuid = player.getUniqueId();
        cachedEyeY.put(uuid, eyeY);
        lastUpdatedY.put(uuid, eyeY);

        var map = new ConcurrentHashMap<Long, WrapperEntity>();
        for (MarkedChunk chunk : getVisibleChunks(player)) {
            map.put(chunkKey(chunk), createEntity(player, chunk, eyeY));
        }
        playerDisplays.put(uuid, map);
    }

    public void removeDisplaysForPlayer(UUID playerId) {
        var map = playerDisplays.remove(playerId);
        if (map != null) map.values().forEach(WrapperEntity::remove);
        cachedEyeY.remove(playerId);
        lastUpdatedY.remove(playerId);
    }

    /** Called when a chunk is newly marked — shows display to nearby players. */
    @Override
    public void onChunkMarked(MarkedChunk chunk) {
        if (!isEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isVisible(player, chunk)) continue;
            var map = playerDisplays.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
            long key = chunkKey(chunk);
            if (!map.containsKey(key)) {
                double eyeY = cachedEyeY.getOrDefault(player.getUniqueId(),
                    player.getEyeLocation().getY());
                map.put(key, createEntity(player, chunk, eyeY));
            }
        }
    }

    /** Called when a chunk is unmarked — removes its display from all players. */
    @Override
    public void onChunkUnmarked(MarkedChunk chunk) {
        if (!isEnabled()) return;
        long key = chunkKey(chunk);
        for (var map : playerDisplays.values()) {
            WrapperEntity entity = map.remove(key);
            if (entity != null) entity.remove();
        }
    }

    /** Called when the player crosses a chunk boundary — updates which markers are visible. */
    public void refreshVisibility(Player player) {
        if (!isEnabled()) return;
        var map = playerDisplays.computeIfAbsent(
            player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Set<Long> shouldBeVisible = new HashSet<>();
        double eyeY = cachedEyeY.getOrDefault(player.getUniqueId(),
            player.getEyeLocation().getY());

        for (MarkedChunk chunk : getVisibleChunks(player)) {
            long key = chunkKey(chunk);
            shouldBeVisible.add(key);
            if (!map.containsKey(key)) {
                map.put(key, createEntity(player, chunk, eyeY));
            }
        }

        Iterator<Map.Entry<Long, WrapperEntity>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!shouldBeVisible.contains(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
            }
        }
    }

    // Runs async — reads cachedEyeY, never calls Bukkit API directly
    private void tickYUpdate() {
        if (!isEnabled()) return;
        double threshold = config.display.yUpdateThreshold;
        double yOffset   = config.display.yOffsetFromEye;
        int    duration  = config.display.updateIntervalTicks;

        for (var playerEntry : playerDisplays.entrySet()) {
            UUID uuid = playerEntry.getKey();
            double eyeY = cachedEyeY.getOrDefault(uuid, 0.0);
            double lastY = lastUpdatedY.getOrDefault(uuid, Double.MIN_VALUE);

            if (Math.abs(eyeY - lastY) < threshold) continue;
            lastUpdatedY.put(uuid, eyeY);

            float targetY = (float) (eyeY + yOffset);
            for (var entityEntry : playerEntry.getValue().entrySet()) {
                WrapperEntity entity = entityEntry.getValue();
                if (!entity.isSpawned()) continue;

                com.github.retrooper.packetevents.protocol.world.Location loc = entity.getLocation();
                double baseY = loc.getY();
                double deltaY = targetY - baseY;

                AbstractDisplayMeta meta = (AbstractDisplayMeta) entity.getEntityMeta();
                meta.setInterpolationDelay(0);
                meta.setTransformationInterpolationDuration(duration);
                meta.setTranslation(new Vector3f(0f, (float) deltaY, 0f));
                entity.refresh();
            }
        }
    }

    private WrapperEntity createEntity(Player player, MarkedChunk chunk, double eyeY) {
        double x = chunk.cx() * 16.0 + 8.5;
        double z = chunk.cz() * 16.0 + 8.5;
        float targetY = (float) Math.min(eyeY + config.display.yOffsetFromEye,
            player.getWorld().getMaxHeight() - 1);

        // Spawn directly at targetY; translation is zero.
        // This keeps the billboard pivot point at targetY (eye level) to prevent culling and scaling issues when player is close.
        WrapperEntity entity = new WrapperEntity(EntityTypes.TEXT_DISPLAY);
        entity.spawn(SpigotConversionUtil.fromBukkitLocation(
            new Location(player.getWorld(), x, targetY, z)));

        TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
        meta.setText(buildDisplayText(chunk));
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.VERTICAL);
        meta.setBackgroundColor(0);
        meta.setLineWidth(300);
        meta.setTextOpacity((byte) -1);
        meta.setInterpolationDelay(0);
        meta.setTransformationInterpolationDuration(0);
        meta.setTranslation(new Vector3f(0f, 0f, 0f));
        meta.setBrightnessOverride((15 << 4) | (15 << 20));

        entity.addViewer(player.getUniqueId());
        return entity;
    }

    private Component buildDisplayText(MarkedChunk chunk) {
        if (structureRegistry != null) {
            // A chunk can sit inside multiple overlapping structures' bounding boxes — list all of them,
            // not just the single structureGroupId the chunk happened to be registered under first.
            List<StructureGroup> groupsHere = structureRegistry.findAllGroupsAt(chunk.world(), chunk.cx(), chunk.cz());
            if (!groupsHere.isEmpty()) {
                var builder = Component.text();
                for (int i = 0; i < groupsHere.size(); i++) {
                    StructureGroup group = groupsHere.get(i);
                    long days = Math.max(0, group.nextRefreshAt() - System.currentTimeMillis()) / 86_400_000L;
                    if (i > 0) builder.appendNewline();
                    builder.append(messages.display.structureLine1.withPlaceholders(
                            Components.placeholder("structure_name", StructureRegistry.displayName(group.structureId()))))
                        .appendNewline()
                        .append(messages.display.structureLine2.withPlaceholders(
                            Components.placeholder("days", String.valueOf(days))));
                }
                return builder.build();
            }
        }

        return Component.text()
            .append(messages.display.line1.asComponent())
            .appendNewline()
            .append(messages.display.line2.withPlaceholders(
                Components.placeholder("cx_cz", chunk.coordDisplay())))
            .appendNewline()
            .append(messages.display.line3.asComponent())
            .build();
    }

    private List<MarkedChunk> getVisibleChunks(Player player) {
        int radius = config.display.renderRadiusChunks;
        int max    = config.display.maxVisibleChunks;
        int pcx = player.getLocation().getBlockX() >> 4;
        int pcz = player.getLocation().getBlockZ() >> 4;
        String world = player.getWorld().getName();

        return markRegistry.getMarkedChunks().stream()
            .filter(c -> c.world().equals(world))
            .filter(c -> Math.abs(c.cx() - pcx) <= radius && Math.abs(c.cz() - pcz) <= radius)
            .sorted(Comparator.comparingInt(c ->
                Math.abs(c.cx() - pcx) + Math.abs(c.cz() - pcz)))
            .limit(max)
            .toList();
    }

    private boolean isVisible(Player player, MarkedChunk chunk) {
        int radius = config.display.renderRadiusChunks;
        int pcx = player.getLocation().getBlockX() >> 4;
        int pcz = player.getLocation().getBlockZ() >> 4;
        return chunk.world().equals(player.getWorld().getName())
            && Math.abs(chunk.cx() - pcx) <= radius
            && Math.abs(chunk.cz() - pcz) <= radius;
    }

    private static long chunkKey(MarkedChunk c) {
        return ((long) c.cx() << 32) | (c.cz() & 0xFFFFFFFFL);
    }

    private boolean isEnabled() {
        return packetEventsAvailable && config.display.enabled;
    }
}

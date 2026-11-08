package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCapability;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorCompatibilitySnapshot;
import github.freshchromatic.chunkrevive.api.worldgen.GeneratorSupportLevel;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative diagnostic adapter. Unknown generators are never considered executable. */
final class DefaultWorldGeneratorCompatibilityApi implements WorldGeneratorCompatibilityApi, Listener {

    private final Map<String, GeneratorCompatibilitySnapshot> snapshots = new ConcurrentHashMap<>();
    private volatile boolean                                  active;

    void start(Plugin plugin) {
        active = true;
        Bukkit.getWorlds().forEach(this::recordWorld);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void stop() {
        active = false;
        snapshots.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (active) {
            recordWorld(event.getWorld());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (active) {
            snapshots.remove(event.getWorld().getName());
        }
    }

    @Override
    public GeneratorCompatibilitySnapshot inspect(String world) {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        return snapshots.getOrDefault(world, unavailable(world, active ? "WORLD_NOT_LOADED" : "API_UNAVAILABLE"));
    }

    @Override
    public Collection<GeneratorCompatibilitySnapshot> worlds() {
        return snapshots.values().stream().sorted(Comparator.comparing(GeneratorCompatibilitySnapshot::world)).toList();
    }

    void recordWorld(World world) {
        Objects.requireNonNull(world, "world");
        ChunkGenerator generator = world.getGenerator();
        if (generator == null) {
            snapshots.put(world.getName(), new GeneratorCompatibilitySnapshot(world.getName(), "minecraft:vanilla",
                GeneratorSupportLevel.NATIVE, Set.of(GeneratorCapability.REGENERATION, GeneratorCapability.BIOME_SCAN,
                    GeneratorCapability.STRUCTURE_REGENERATION, GeneratorCapability.BLOCK_POPULATORS,
                    GeneratorCapability.PARALLEL_GENERATION), Optional.of("vanilla"), Optional.of("bundled"),
                Optional.of(fingerprint(world, "minecraft:vanilla")), Optional.empty(), List.of()));
            return;
        }
        String generatorClass = generator.getClass().getName();
        snapshots.put(world.getName(), new GeneratorCompatibilitySnapshot(world.getName(), generatorClass,
            GeneratorSupportLevel.UNSUPPORTED, Set.of(), Optional.empty(), Optional.empty(), Optional.of(fingerprint(
                world, generatorClass)), Optional.of("UNSUPPORTED_CHUNK_GENERATOR"), List.of(
                    "No verified ChunkRevive adapter exists for this Bukkit generator.")));
    }

    private static GeneratorCompatibilitySnapshot unavailable(String world, String reason) {
        return new GeneratorCompatibilitySnapshot(world, "", GeneratorSupportLevel.UNAVAILABLE, Set.of(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason), List.of());
    }

    private static String fingerprint(World world, String generator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((world.getUID() + ":" + world.getSeed() + ":" + world.getKey() + ":" + generator).getBytes(
                StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

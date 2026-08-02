package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.worldgen.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;

/** Conservative diagnostic adapter. Unknown generators are never considered executable. */
final class DefaultWorldGeneratorCompatibilityApi implements WorldGeneratorCompatibilityApi {
    @Override public GeneratorCompatibilitySnapshot inspect(String world) {
        World loaded = Bukkit.getWorld(world);
        if (loaded == null) return unavailable(world, "WORLD_NOT_LOADED");
        if (loaded.getGenerator() == null) {
            return new GeneratorCompatibilitySnapshot(world, "minecraft:vanilla", GeneratorSupportLevel.NATIVE,
                Set.of(GeneratorCapability.REGENERATION, GeneratorCapability.BIOME_SCAN,
                    GeneratorCapability.STRUCTURE_REGENERATION, GeneratorCapability.BLOCK_POPULATORS,
                    GeneratorCapability.PARALLEL_GENERATION), Optional.of("vanilla"), Optional.of("bundled"),
                Optional.of(fingerprint(loaded, "minecraft:vanilla")), Optional.empty(), List.of());
        }
        String generator = loaded.getGenerator().getClass().getName();
        return new GeneratorCompatibilitySnapshot(world, generator, GeneratorSupportLevel.UNSUPPORTED, Set.of(),
            Optional.empty(), Optional.empty(), Optional.of(fingerprint(loaded, generator)), Optional.of("UNSUPPORTED_CHUNK_GENERATOR"),
            List.of("No verified ChunkRevive adapter exists for this Bukkit generator."));
    }
    @Override public Collection<GeneratorCompatibilitySnapshot> worlds() {
        return Bukkit.getWorlds().stream().map(world -> inspect(world.getName())).toList();
    }
    private static GeneratorCompatibilitySnapshot unavailable(String world, String reason) {
        return new GeneratorCompatibilitySnapshot(world, "", GeneratorSupportLevel.UNAVAILABLE, Set.of(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(reason), List.of());
    }
    private static String fingerprint(World world, String generator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((world.getUID() + ":" + world.getSeed() + ":" + world.getKey() + ":" + generator).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}

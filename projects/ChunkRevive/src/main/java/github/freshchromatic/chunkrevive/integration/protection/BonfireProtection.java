package github.freshchromatic.chunkrevive.integration.protection;

import com.foenichs.bonfire.Bonfire;
import com.foenichs.bonfire.model.ChunkLayer;
import com.foenichs.bonfire.storage.ClaimRegistry;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.UUID;

final class BonfireProtection extends ExternalProtection {
    private final ClaimRegistry registry;

    BonfireProtection(Plugin plugin) {
        super("Bonfire");
        this.registry = readRegistry(plugin);
    }

    private static ClaimRegistry readRegistry(Plugin plugin) {
        try {
            // Bonfire 1.6.1 exposes no public accessor for its live claim registry.
            Field field = Bonfire.class.getDeclaredField("registry");
            field.setAccessible(true);
            return Objects.requireNonNull((ClaimRegistry) field.get(plugin), "Bonfire claim registry is not initialized");
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalStateException("Cannot access the Bonfire claim registry", exception);
        }
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return hasClaim(registry, world.getUID(), x, z);
    }

    static boolean hasClaim(ClaimRegistry registry, UUID worldId, int x, int z) {
        long key = Chunk.getChunkKey(x, z);
        return registry.getAt(worldId, key, ChunkLayer.GROUND) != null
            || registry.getAt(worldId, key, ChunkLayer.ROOF) != null;
    }
}

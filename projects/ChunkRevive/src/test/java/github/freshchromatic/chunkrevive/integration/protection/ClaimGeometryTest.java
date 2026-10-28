package github.freshchromatic.chunkrevive.integration.protection;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import com.foenichs.bonfire.model.Claim;
import com.foenichs.bonfire.model.ChunkLayer;
import com.foenichs.bonfire.model.ChunkPos;
import com.foenichs.bonfire.storage.ClaimRegistry;
import me.tayebyassine.homestead.Homestead;
import me.tayebyassine.homestead.database.cache.PositionIndexedChunkCache;
import me.tayebyassine.homestead.models.RegionChunk;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimGeometryTest {
    @Test
    void dominionUsesExclusiveUpperBoundsAcrossNegativeChunks() {
        var cuboid = new CuboidDTO(-16, 30, -1, 1, 40, 1);
        assertTrue(DominionProtection.intersects(cuboid, -1, -1));
        assertTrue(DominionProtection.intersects(cuboid, 0, 0));
        assertFalse(DominionProtection.intersects(cuboid, -2, 0));
        assertFalse(DominionProtection.intersects(cuboid, 1, 0));
        assertFalse(DominionProtection.intersects(cuboid, 0, 1));
    }

    @Test
    void bonfireProtectsBothLayersAndKeepsWorldsSeparate() {
        UUID world = UUID.randomUUID();
        UUID otherWorld = UUID.randomUUID();
        var positions = new HashSet<ChunkPos>();
        positions.add(new ChunkPos(world, Chunk.getChunkKey(-2, 3), ChunkLayer.ROOF));
        positions.add(new ChunkPos(world, Chunk.getChunkKey(4, -5), ChunkLayer.GROUND));
        var claim = new Claim(1, UUID.randomUUID(), positions, "", "", new HashSet<>(), new HashSet<>(), new HashSet<>());
        var registry = new ClaimRegistry(List.of(claim));

        assertTrue(BonfireProtection.hasClaim(registry, world, -2, 3));
        assertTrue(BonfireProtection.hasClaim(registry, world, 4, -5));
        assertFalse(BonfireProtection.hasClaim(registry, world, -2, 4));
        assertFalse(BonfireProtection.hasClaim(registry, otherWorld, -2, 3));
    }

    @Test
    void homesteadQueriesTheWorldSpecificPositionIndex() {
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        World otherWorld = world(UUID.randomUUID());
        var previous = Homestead.POSITION_INDEXED_CHUNK_CACHE;
        try {
            Homestead.POSITION_INDEXED_CHUNK_CACHE = new PositionIndexedChunkCache();
            Homestead.POSITION_INDEXED_CHUNK_CACHE.add(new RegionChunk(1, 2, worldId, -3, 5, 0, false));
            var protection = new HomesteadProtection();
            assertTrue(protection.hasClaim(world, -3, 5));
            assertFalse(protection.hasClaim(world, -3, 6));
            assertFalse(protection.hasClaim(otherWorld, -3, 5));
        } finally {
            Homestead.POSITION_INDEXED_CHUNK_CACHE = previous;
        }
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, arguments) -> method.getName().equals("getUID") ? id : null);
    }
}

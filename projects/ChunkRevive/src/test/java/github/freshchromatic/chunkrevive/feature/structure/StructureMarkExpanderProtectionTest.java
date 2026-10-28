package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.nms.BlockBounds;
import github.freshchromatic.chunkrevive.nms.StructureInfo;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureMarkExpanderProtectionTest {
    @Test
    void abortedStructureFallsBackToItsUnprotectedStartingChunk() {
        var config = new PluginConfig();
        config.structure.enabled = true;
        config.structure.mark.expandToFullStructure = true;
        LandProtection protection = new LandProtection() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean hasClaim(World world, int x, int z) { return false; }
            @Override public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
                    PluginConfig.Structure.PartialClaimPolicy policy) { return null; }
        };
        var world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> method.getName().equals("getName") ? "world" : null);
        var detector = new StructureDetector(config, (w, x, z, radius, filter) ->
            List.of(new StructureInfo("minecraft:village", new BlockBounds(0, 0, 0, 31, 64, 15))));
        var registry = new StructureRegistry(null, config, protection);
        var expander = new StructureMarkExpander(config, detector, registry, protection);

        var chunks = expander.expand(world, 0, 0, UUID.randomUUID());
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().cx());
        assertEquals(0, chunks.getFirst().cz());
    }

    @Test
    void claimedStartingChunkIsRejectedBeforeStructureExpansion() {
        var config = new PluginConfig();
        config.structure.enabled = true;
        config.structure.mark.expandToFullStructure = true;
        LandProtection protection = new LandProtection() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean hasClaim(World world, int x, int z) { return true; }
            @Override public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
                    PluginConfig.Structure.PartialClaimPolicy policy) {
                throw new AssertionError("A protected starting chunk must not be expanded");
            }
        };
        var expander = new StructureMarkExpander(config, null, null, protection);

        assertTrue(expander.expand(null, 2, 3, UUID.randomUUID()).isEmpty());
    }
}

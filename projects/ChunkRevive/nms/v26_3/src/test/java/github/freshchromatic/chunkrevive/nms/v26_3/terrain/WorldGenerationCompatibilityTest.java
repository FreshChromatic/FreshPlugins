package github.freshchromatic.chunkrevive.nms.v26_3.terrain;

import github.freshchromatic.chunkrevive.nms.v26_3.V26_3NmsPlatformProvider;
import github.freshchromatic.chunkrevive.nms.ChunkStage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldGenerationCompatibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void seededRegionCanResolveTheServerWorldGenerationFields() {
        assertDoesNotThrow(() -> Class.forName(SeededWorldGenRegion.class.getName()));
    }

    @Test
    void providerCreatesTheMinecraft263Platform() {
        V26_3NmsPlatformProvider provider = new V26_3NmsPlatformProvider();

        assertEquals(Set.of("26.3"), provider.supportedMinecraftVersions());
        assertDoesNotThrow(provider::create);
    }

    @Test
    void terrainChunksMeetLegacyGenerationThresholdsBeforeFeatures() {
        ChunkStage persisted = ChunkStage.persisted(ChunkStatus.TERRAIN.getName());

        assertEquals(ChunkStage.CARVERS, persisted);
        assertFalse(persisted.isBefore(ChunkStage.SURFACE));
        assertTrue(persisted.isBefore(ChunkStage.FEATURES));
        assertEquals(persisted, ChunkStage.configured("TERRAIN"));
        assertEquals(persisted, ChunkStage.persisted("minecraft:carvers"));
    }
}

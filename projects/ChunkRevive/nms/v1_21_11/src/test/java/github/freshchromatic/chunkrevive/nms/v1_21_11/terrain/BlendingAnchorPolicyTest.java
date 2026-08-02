package github.freshchromatic.chunkrevive.nms.v1_21_11.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlendingAnchorPolicyTest {

    @Test
    void regenerationTargetHasTheSameFreshTerrainIdentityAsADeletedChunk() {
        assertFalse(BlendingAnchorPolicy.preserveHistoricalTerrainMetadata(true));
    }

    @Test
    void survivingContextChunkRemainsAvailableAsAnOldNoiseAnchor() {
        assertTrue(BlendingAnchorPolicy.preserveHistoricalTerrainMetadata(false));
    }

    @Test
    void oldNoiseLoadsTheFullBlenderDependencyRadius() {
        assertEquals(10, BlendingAnchorPolicy.requiredDiskContextRadius(3, true));
        assertEquals(12, BlendingAnchorPolicy.requiredDiskContextRadius(12, true));
        assertEquals(3, BlendingAnchorPolicy.requiredDiskContextRadius(3, false));
    }

    @Test
    void contextDependentOldNoiseTerrainDoesNotReuseCarverSnapshots() {
        assertFalse(BlendingAnchorPolicy.mayUseContextFreeCarverCache(true));
        assertTrue(BlendingAnchorPolicy.mayUseContextFreeCarverCache(false));
    }
}

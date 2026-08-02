package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.nms.CarverStageCachePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarverStageCachePolicyTest {
    @Test
    void reusesTargetsAndOldNoiseOnlyForExactConsecutiveScope() {
        assertFalse(CarverStageCachePolicy.mayReuse(true, true, false, false));
        assertFalse(CarverStageCachePolicy.mayReuse(true, false, false, true));
        assertTrue(CarverStageCachePolicy.mayReuse(true, true, true, true));
        assertTrue(CarverStageCachePolicy.mayReuse(true, false, false, false));
    }

    @Test
    void retainsEveryStageForSmallInteractiveRegeneration() {
        assertTrue(CarverStageCachePolicy.shouldRetain(
            true, true, true, 8, 8, 0, 15, 0, 15));
    }

    @Test
    void largeBulkRetainsHaloAndBoundaryButNotInteriorTargets() {
        assertTrue(CarverStageCachePolicy.shouldRetain(
            true, false, false, -1, 8, 0, 15, 0, 15));
        assertTrue(CarverStageCachePolicy.shouldRetain(
            true, false, true, 1, 8, 0, 15, 0, 15));
        assertFalse(CarverStageCachePolicy.shouldRetain(
            true, false, true, 8, 8, 0, 15, 0, 15));
    }

    @Test
    void disabledCacheNeverRetainsSnapshots() {
        assertFalse(CarverStageCachePolicy.shouldRetain(
            false, true, false, 0, 0, 0, 0, 0, 0));
    }
}

package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.nms.ChunkArea;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAreaTest {

    @Test
    void includesEdgesAndExcludesCoordinatesOutsideTheArea() {
        var area = new ChunkArea(-4, 2, -3, 5);
        assertTrue(area.contains(-4, -3));
        assertTrue(area.contains(2, 5));
        assertTrue(area.contains(0, 0));
        assertFalse(area.contains(-5, 0));
        assertFalse(area.contains(3, 0));
        assertFalse(area.contains(0, -4));
        assertFalse(area.contains(0, 6));
    }

    @Test
    void rejectsReversedBoundsOnEitherAxis() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkArea(2, -4, -3, 5));
        assertThrows(IllegalArgumentException.class, () -> new ChunkArea(-4, 2, 5, -3));
    }
}

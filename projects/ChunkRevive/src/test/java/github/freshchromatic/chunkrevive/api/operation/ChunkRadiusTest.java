package github.freshchromatic.chunkrevive.api.operation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkRadiusTest {

    @Test
    void rejectsMissingWorldName() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkRadius(null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkRadius(" ", 0, 0, 0));
    }

    @Test
    void rejectsNegativeRadius() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkRadius("world", 0, 0, -1));
    }

    @Test
    void acceptsZeroRadius() {
        var radius = new ChunkRadius("world", 4, -8, 0);

        assertEquals("world", radius.world());
        assertEquals(4, radius.centerX());
        assertEquals(-8, radius.centerZ());
        assertEquals(0, radius.radius());
    }
}

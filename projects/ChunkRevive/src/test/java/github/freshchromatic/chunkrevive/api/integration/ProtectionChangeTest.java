package github.freshchromatic.chunkrevive.api.integration;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtectionChangeTest {

    @Test
    void chunkAreaRequiresWorldAndOrderedInclusiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkArea(" ", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkArea("world", 2, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkArea("world", 0, 0, 2, 1));

        var area = new ChunkArea("world", -3, 4, -2, 5);
        assertEquals(-3, area.minChunkX());
        assertEquals(4, area.maxChunkX());
        assertEquals(-2, area.minChunkZ());
        assertEquals(5, area.maxChunkZ());
    }

    @Test
    void protectionChangeRequiresTypeAndNormalizesMissingArea() {
        assertThrows(NullPointerException.class, () -> new ProtectionChange(null, Optional.empty()));

        assertEquals(Optional.empty(), new ProtectionChange(ProtectionChangeType.CREATED, null).area());
    }
}

package github.freshchromatic.chunkrevive.nms;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainGenerationCancellationTest {

    @Test
    void cancellationIsCheckedAtTheWriteBoundary() {
        assertDoesNotThrow(() -> TerrainGenerationCancellation.throwIfCancelled(() -> false));
        assertThrows(CancellationException.class, () -> TerrainGenerationCancellation.throwIfCancelled(() -> true));
    }
}

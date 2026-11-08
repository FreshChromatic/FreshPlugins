package github.freshchromatic.chunkrevive.nms;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class TerrainGenerationCancellation {

    private TerrainGenerationCancellation() {
    }

    public static void throwIfCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean()) {
            throw new CancellationException("Terrain generation cancelled");
        }
    }
}

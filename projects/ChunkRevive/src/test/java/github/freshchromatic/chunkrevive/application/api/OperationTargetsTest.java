package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.operation.AllMarked;
import github.freshchromatic.chunkrevive.api.operation.ChunkRadius;
import github.freshchromatic.chunkrevive.api.operation.ExplicitChunks;
import github.freshchromatic.chunkrevive.api.operation.MarkScope;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationTargetsTest {

    private static final UUID PLAYER    = new UUID(0, 1);
    private static final UUID STRUCTURE = new UUID(0, 2);

    private final MarkedChunk       independent = new MarkedChunk("world", -2, 3, PLAYER, 1);
    private final MarkedChunk       structure   = new MarkedChunk("world", -1, 4, PLAYER, 1, STRUCTURE);
    private final MarkedChunk       otherWorld  = new MarkedChunk("other", -2, 3, PLAYER, 1);
    private final List<MarkedChunk> marks       = List.of(independent, structure, otherWorld);

    @Test
    void explicitSelectionIncludesOnlyMarkedCoordinatesInTheCorrectWorld() {
        ExplicitChunks selection = new ExplicitChunks(List.of(new ChunkKey("world", -2, 3), new ChunkKey("world", 100,
            100)));

        assertEquals(List.of(independent), OperationTargets.resolve(selection, marks));
    }

    @Test
    void allMarkedSelectionRespectsWorldAndStructureScope() {
        assertEquals(List.of(independent, structure), OperationTargets.resolve(new AllMarked(Optional.of("world"),
            MarkScope.ALL), marks));
        assertEquals(List.of(structure), OperationTargets.resolve(new AllMarked(Optional.empty(), MarkScope.STRUCTURES),
            marks));
        assertEquals(List.of(independent, otherWorld), OperationTargets.resolve(new AllMarked(Optional.empty(),
            MarkScope.INDEPENDENT), marks));
    }

    @Test
    void radiusSelectionIncludesSquareBoundariesOnlyInTheRequestedWorld() {
        assertEquals(List.of(independent, structure), OperationTargets.resolve(new ChunkRadius("world", -1, 3, 1),
            marks));
        assertEquals(List.of(independent), OperationTargets.resolve(new ChunkRadius("world", -2, 3, 0), marks));
    }

    @Test
    void radiusSelectionDoesNotWrapAtIntegerCoordinateLimits() {
        var extremeCoordinate = new MarkedChunk("world", Integer.MAX_VALUE, 0, PLAYER, 1);

        assertEquals(List.of(), OperationTargets.resolve(new ChunkRadius("world", Integer.MIN_VALUE, 0, 1), List.of(
            extremeCoordinate)));
    }
}

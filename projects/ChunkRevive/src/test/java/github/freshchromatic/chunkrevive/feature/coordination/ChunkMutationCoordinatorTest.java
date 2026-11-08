package github.freshchromatic.chunkrevive.feature.coordination;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMutationCoordinatorTest {

    @Test
    void overlappingRequestsRunInSequence() {
        var coordinator = new ChunkMutationCoordinator();
        var target = new ChunkKey("world", 2, -3);
        var firstOperation = new CompletableFuture<Void>();
        var secondStarts = new AtomicInteger();

        var first = coordinator.run(List.of(target), () -> firstOperation);
        var second = coordinator.run(List.of(target), () -> {
            secondStarts.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(0, secondStarts.get());

        firstOperation.complete(null);

        first.join();
        second.join();
        assertEquals(1, secondStarts.get());
    }

    @Test
    void disjointRequestsRunConcurrently() {
        var coordinator = new ChunkMutationCoordinator();
        var firstOperation = new CompletableFuture<Void>();
        var secondOperation = new CompletableFuture<Void>();
        var operationStarts = new AtomicInteger();
        var firstTarget = new ChunkKey("world", 2, -3);
        var secondTarget = new ChunkKey("world", 3, -3);

        var first = coordinator.run(List.of(firstTarget), () -> {
            operationStarts.incrementAndGet();
            return firstOperation;
        });
        var second = coordinator.run(List.of(secondTarget), () -> {
            operationStarts.incrementAndGet();
            return secondOperation;
        });

        assertEquals(2, operationStarts.get());
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        firstOperation.complete(null);
        secondOperation.complete(null);
        first.join();
        second.join();
    }

    @Test
    void multiTargetReservationDoesNotAllowPartialOverlapToPass() {
        var coordinator = new ChunkMutationCoordinator();
        var firstTarget = new ChunkKey("world", 2, -3);
        var secondTarget = new ChunkKey("world", 3, -3);
        var firstOperation = new CompletableFuture<Void>();
        var secondOperation = new CompletableFuture<Void>();
        var laterStarts = new AtomicInteger();

        var first = coordinator.run(List.of(firstTarget), () -> firstOperation);
        var second = coordinator.run(List.of(firstTarget, secondTarget), () -> secondOperation);
        var later = coordinator.run(List.of(secondTarget), () -> {
            laterStarts.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(0, laterStarts.get());

        firstOperation.complete(null);
        assertEquals(0, laterStarts.get());

        secondOperation.complete(null);
        first.join();
        second.join();
        later.join();
        assertEquals(1, laterStarts.get());
    }

    @Test
    void cancellingQueuedReservationDoesNotReleaseItBeforeItsPredecessor() {
        var coordinator = new ChunkMutationCoordinator();
        var target = new ChunkKey("world", 2, -3);
        var firstOperation = new CompletableFuture<Void>();
        var cancelledStarts = new AtomicInteger();
        var laterStarts = new AtomicInteger();

        var first = coordinator.run(List.of(target), () -> firstOperation);
        var cancelled = coordinator.run(List.of(target), () -> {
            cancelledStarts.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        var later = coordinator.run(List.of(target), () -> {
            laterStarts.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(cancelled.cancel(false));
        assertEquals(0, cancelledStarts.get());
        assertEquals(0, laterStarts.get());

        firstOperation.complete(null);

        first.join();
        later.join();
        assertEquals(0, cancelledStarts.get());
        assertEquals(1, laterStarts.get());
    }

    @Test
    void deletionCanAcquireOnlyWhenNoRegenerationOwnsTheTarget() {
        var coordinator = new ChunkMutationCoordinator();
        var target = new ChunkKey("world", 2, -3);
        var generation = new CompletableFuture<Void>();
        var regeneration = coordinator.run(List.of(target), () -> generation);

        assertNull(coordinator.tryAcquire(List.of(target)));
        var disjointLease = coordinator.tryAcquire(List.of(new ChunkKey("world", 3, -3)));
        assertNotNull(disjointLease);
        disjointLease.close();

        generation.complete(null);
        regeneration.join();

        var deletionLease = coordinator.tryAcquire(List.of(target));
        assertNotNull(deletionLease);
        deletionLease.close();
    }

    @Test
    void regionReservationBlocksTargetsInsideThatRegion() {
        var coordinator = new ChunkMutationCoordinator();
        var region = new java.util.ArrayList<ChunkKey>();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                region.add(new ChunkKey("world", x, z));
            }
        }

        var regionLease = coordinator.tryAcquire(region);
        var outsideLease = coordinator.tryAcquire(List.of(new ChunkKey("world", 32, 0)));

        assertNotNull(regionLease);
        assertNull(coordinator.tryAcquire(List.of(new ChunkKey("world", 15, 15))));
        assertNotNull(outsideLease);

        regionLease.close();
        outsideLease.close();
    }
}

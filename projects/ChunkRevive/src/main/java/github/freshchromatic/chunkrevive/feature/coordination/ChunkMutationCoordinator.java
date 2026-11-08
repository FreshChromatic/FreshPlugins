package github.freshchromatic.chunkrevive.feature.coordination;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class ChunkMutationCoordinator {

    private final Map<ChunkKey, CompletableFuture<Void>> reservations = new HashMap<>();

    public CompletableFuture<Void> run(Collection<ChunkKey> targets, Supplier<CompletableFuture<Void>> operation) {
        CompletableFuture<Lease> leaseFuture = acquire(targets);
        CompletableFuture<Void> result = new CompletableFuture<>();
        leaseFuture.whenComplete((lease, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            if (result.isCancelled()) {
                lease.close();
                return;
            }
            try {
                Objects.requireNonNull(operation.get(), "operation future").whenComplete((ignored,
                    operationFailure) -> {
                        lease.close();
                        if (operationFailure == null) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(operationFailure);
                        }
                    });
            } catch (Throwable operationFailure) {
                lease.close();
                result.completeExceptionally(operationFailure);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                leaseFuture.cancel(false);
            }
        });
        return result;
    }

    public CompletableFuture<Lease> acquire(Collection<ChunkKey> targets) {
        ReservationRequest request = reserve(targets);
        CompletableFuture<Lease> result = new CompletableFuture<>();
        AtomicReference<AcquireState> state = new AtomicReference<>(AcquireState.WAITING);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled() && state.compareAndSet(AcquireState.WAITING, AcquireState.RELEASED)) {
                request.available().whenComplete((available, availabilityFailure) -> release(request));
            }
        });
        request.available().whenComplete((ignored, failure) -> {
            if (!state.compareAndSet(AcquireState.WAITING, AcquireState.ACQUIRED)) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                state.set(AcquireState.RELEASED);
                release(request);
                return;
            }

            Lease lease = new Lease(request);
            if (!result.complete(lease)) {
                lease.close();
            }
        });
        return result;
    }

    public Lease tryAcquire(Collection<ChunkKey> targets) {
        List<ChunkKey> keys = targets.stream().distinct().toList();
        ReservationRequest request;
        synchronized (this) {
            boolean occupied = keys.stream()
                .map(reservations::get)
                .anyMatch(reservation -> reservation != null
                && !reservation.isDone());
            if (occupied) {
                return null;
            }
            CompletableFuture<Void> reservation = new CompletableFuture<>();
            request = new ReservationRequest(keys, reservation, CompletableFuture.completedFuture(null));
            keys.forEach(key -> reservations.put(key, reservation));
        }
        return new Lease(request);
    }

    private ReservationRequest reserve(Collection<ChunkKey> targets) {
        List<ChunkKey> keys = targets.stream().distinct().toList();
        CompletableFuture<Void> reservation = new CompletableFuture<>();
        CompletableFuture<Void> available;
        synchronized (this) {
            CompletableFuture<?>[] pending = keys.stream()
                .map(reservations::get)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(CompletableFuture<?>[]::new);
            available = CompletableFuture.allOf(pending);
            keys.forEach(key -> reservations.put(key, reservation));
        }
        return new ReservationRequest(keys, reservation, available);
    }

    private void release(ReservationRequest request) {
        request.reservation().complete(null);
        synchronized (this) {
            request.keys().forEach(key -> reservations.remove(key, request.reservation()));
        }
    }

    public final class Lease implements AutoCloseable {

        private final ReservationRequest request;
        private final AtomicBoolean      closed = new AtomicBoolean();

        private Lease(ReservationRequest request) {
            this.request = request;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(request);
            }
        }
    }

    private enum AcquireState {
        WAITING, ACQUIRED, RELEASED
    }

    private record ReservationRequest(List<ChunkKey> keys, CompletableFuture<Void> reservation,
        CompletableFuture<Void> available) {}
}

package github.freshchromatic.chunkrevive.feature.structure;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class StructureProtectionTick {

    record Result(Set<UUID> activeGroupIds, boolean complete) {

        Result {
            activeGroupIds = Set.copyOf(activeGroupIds);
        }
    }

    private final Set<UUID>        activeGroupIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger    remainingPlayers;
    private final AtomicBoolean    completed      = new AtomicBoolean();
    private final AtomicBoolean    completeScan   = new AtomicBoolean(true);
    private final Consumer<Result> completion;

    StructureProtectionTick(int playerCount, Consumer<Result> completion) {
        if (playerCount < 0) {
            throw new IllegalArgumentException("Player count cannot be negative");
        }
        this.remainingPlayers = new AtomicInteger(playerCount);
        this.completion = completion;
        if (playerCount == 0) {
            complete();
        }
    }

    void playerCompleted(Collection<UUID> activeGroups) {
        activeGroupIds.addAll(activeGroups);
        if (remainingPlayers.decrementAndGet() == 0) {
            complete();
        }
    }

    void playerFailed(Collection<UUID> activeGroups) {
        completeScan.set(false);
        playerCompleted(activeGroups);
    }

    private void complete() {
        if (completed.compareAndSet(false, true)) {
            completion.accept(new Result(activeGroupIds, completeScan.get()));
        }
    }
}

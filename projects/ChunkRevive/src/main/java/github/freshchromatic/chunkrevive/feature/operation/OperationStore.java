package github.freshchromatic.chunkrevive.feature.operation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence port for immutable operation history and restart recovery. */
public interface OperationStore {
    void save(OperationRecord operation);
    void update(OperationRecord operation);
    List<OperationRecord> loadOperations();
}

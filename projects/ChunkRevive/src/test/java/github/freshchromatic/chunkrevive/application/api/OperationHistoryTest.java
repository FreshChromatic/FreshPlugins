package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.operation.OperationId;
import github.freshchromatic.chunkrevive.api.operation.OperationPersistenceStatus;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationHistoryTest {

    @Test
    void recoversNonTerminalOperationAsFailed() {
        var store = mock(OperationStore.class);
        UUID id = UUID.randomUUID();
        var interrupted = new OperationRecord(id, "RESET", "RUNNING", 1, 3, 100, 200, "owner", "request", null, null,
            Map.of(), List.of());
        when(store.loadOperations()).thenReturn(List.of(interrupted));

        var history = new OperationHistory(store);
        var snapshot = history.find(id).orElseThrow();

        assertEquals(new OperationId(id), snapshot.id());
        assertEquals(OperationState.FAILED, snapshot.state());
        assertEquals(OperationPersistenceStatus.PERSISTED, snapshot.persistenceStatus());
        assertEquals("OPERATION_INTERRUPTED_BY_RESTART", snapshot.failure().orElseThrow().code());
        assertEquals("Operation was interrupted by server restart.", snapshot.failure().orElseThrow().message());
        assertEquals(List.of(), snapshot.deletionJobIds());
        assertTrue(history.contains(id));
        verify(store).update(argThat(record -> record.id().equals(id) && record.state().equals("FAILED")
            && record.completed() == 1 && record.total() == 3));
    }

    @Test
    void preservesTerminalHistoryWithoutWritingItAgain() {
        var store = mock(OperationStore.class);
        UUID id = UUID.randomUUID();
        var completed = new OperationRecord(id, "RESET", "SUCCEEDED", 3, 3, 100, 200, "owner", "request", null, null,
            Map.of(), List.of());
        when(store.loadOperations()).thenReturn(List.of(completed));

        var history = new OperationHistory(store);

        assertEquals(OperationState.SUCCEEDED, history.find(id).orElseThrow().state());
        assertFalse(history.contains(UUID.randomUUID()));
        assertEquals(1, history.snapshots().size());
        verify(store).loadOperations();
        verify(store, never()).update(any());
    }
}

package github.freshchromatic.chunkrevive.api.operation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationValueObjectTest {

    @Test
    void operationIdRequiresUuid() {
        assertThrows(IllegalArgumentException.class, () -> new OperationId(null));

        var id = UUID.randomUUID();
        assertEquals(id, new OperationId(id).value());
    }

    @Test
    void previewTokenRequiresNonblankValue() {
        assertThrows(IllegalArgumentException.class, () -> new PreviewToken(null));
        assertThrows(IllegalArgumentException.class, () -> new PreviewToken(" "));

        assertEquals("token", new PreviewToken("token").value());
    }

    @Test
    void legacyOperationSnapshotConstructorReportsUnknownPersistenceStatus() {
        var snapshot = new OperationSnapshot(new OperationId(UUID.randomUUID()), OperationType.REGENERATE,
            OperationState.QUEUED, 0, 1, Instant.EPOCH, Instant.EPOCH, Optional.empty(), Optional.empty(),
            Optional.empty(), List.of());

        assertEquals(OperationPersistenceStatus.UNKNOWN, snapshot.persistenceStatus());
    }
}

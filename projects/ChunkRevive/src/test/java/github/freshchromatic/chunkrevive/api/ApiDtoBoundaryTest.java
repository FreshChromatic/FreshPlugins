package github.freshchromatic.chunkrevive.api;

import github.freshchromatic.chunkrevive.api.mark.BulkMarkResult;
import github.freshchromatic.chunkrevive.api.mark.MarkChangeResult;
import github.freshchromatic.chunkrevive.api.mark.MarkChangeStatus;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.model.ApiError;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.OperationHandle;
import github.freshchromatic.chunkrevive.api.operation.OperationId;
import github.freshchromatic.chunkrevive.api.operation.OperationPage;
import github.freshchromatic.chunkrevive.api.operation.OperationPreview;
import github.freshchromatic.chunkrevive.api.operation.OperationState;
import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import github.freshchromatic.chunkrevive.api.operation.PreviewRejection;
import github.freshchromatic.chunkrevive.api.operation.AllMarked;
import github.freshchromatic.chunkrevive.api.operation.MarkScope;
import github.freshchromatic.chunkrevive.api.operation.PreviewToken;
import github.freshchromatic.chunkrevive.api.structure.StructurePage;
import github.freshchromatic.chunkrevive.api.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiDtoBoundaryTest {

    @Test
    void apiErrorsRequireCodeAndMessage() {
        assertThrows(IllegalArgumentException.class, () -> new ApiError(" ", "message", null));
        assertThrows(IllegalArgumentException.class, () -> new ApiError("CODE", " ", null));
    }

    @Test
    void apiVersionIncludesDeferredCancellationState() {
        assertEquals(new ApiVersion(1, 3, 0), ApiVersion.CURRENT);
        assertEquals(OperationState.CANCELLING, OperationState.valueOf("CANCELLING"));
    }

    @Test
    void requestContextNormalizesOptionalReferencesAndRejectsBlankCorrelationIds() {
        var context = new RequestContext(null, null);

        assertTrue(context.actor().isEmpty());
        assertTrue(context.correlationId().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> context.withCorrelationId(" "));
    }

    @Test
    void allMarkedSelectionRejectsBlankWorldAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new AllMarked(Optional.of(" "), MarkScope.INDEPENDENT));
    }

    @Test
    void operationSnapshotsRequireTheirDomainState() {
        var id = new OperationId(UUID.randomUUID());
        assertThrows(NullPointerException.class, () -> new OperationHandle(null));
        assertThrows(NullPointerException.class, () -> new OperationSnapshot(id, null, null, 0, 0, Instant.EPOCH,
            Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new OperationSnapshot(id, OperationType.REGENERATE,
            OperationState.RUNNING, 2, 1, Instant.EPOCH, Instant.EPOCH, Optional.empty(), Optional.empty(),
            Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new OperationPage(-1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StructurePage(-1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ApiVersion(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OperationPreview(new PreviewToken("token"),
            Instant.EPOCH, true, 1, 0, 0, 0, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new OperationPreview(new PreviewToken("token"),
            Instant.EPOCH, false, 1, 1, 1, 0, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new OperationPreview(new PreviewToken("token"),
            Instant.EPOCH, true, 1, 1, 0, 0, 0, 0, List.of(new PreviewRejection(Optional.empty(), "REJECTED",
                Optional.empty()))));
        assertThrows(IllegalArgumentException.class, () -> new OperationPreview(new PreviewToken("token"),
            Instant.EPOCH, false, 1, 1, 0, 0, 0, 0, List.of(new PreviewRejection(Optional.empty(), "REJECTED",
                Optional.empty()))));

        var operation = new OperationSnapshot(id, OperationType.REGENERATE, OperationState.SUCCEEDED, 1, 1,
            Instant.EPOCH, Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty());
        var structure = new StructureSnapshot(UUID.randomUUID(), "world", "test:structure", 0, 0, 0, 0, Instant.EPOCH,
            Optional.empty(), false);
        assertThrows(IllegalArgumentException.class, () -> new OperationPage(0, List.of(operation)));
        assertThrows(IllegalArgumentException.class, () -> new StructurePage(0, List.of(structure)));
    }

    @Test
    void bulkMarkResultValidatesCountsAndOwnsItsResults() {
        var key = new ChunkKey("world", 1, 2);
        var results = new ArrayList<>(List.of(new MarkChangeResult(key, MarkChangeStatus.ADDED, Optional.empty())));
        var result = new BulkMarkResult(1, 1, 0, 0, results);
        results.clear();

        assertEquals(1, result.results().size());
        assertThrows(IllegalArgumentException.class, () -> new BulkMarkResult(1, 0, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BulkMarkResult(1, 0, 1, 0, List.of(new MarkChangeResult(
            key, MarkChangeStatus.ADDED, Optional.empty()))));
    }

    @Test
    void protectionBatchResultRejectsBlankReasonCodes() {
        var chunk = new ChunkKey("world", 1, 2);

        assertThrows(IllegalArgumentException.class, () -> new ProtectionBatchResult(Map.of(), Map.of(chunk, " ")));
    }
}

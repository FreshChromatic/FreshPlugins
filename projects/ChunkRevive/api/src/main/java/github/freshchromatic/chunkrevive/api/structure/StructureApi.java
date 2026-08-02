package github.freshchromatic.chunkrevive.api.structure;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.model.RequestContext;
import github.freshchromatic.chunkrevive.api.operation.OperationHandle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface StructureApi {
    Optional<StructureSnapshot> find(UUID groupId);
    List<StructureSnapshot> findAt(ChunkKey chunk);
    StructurePage list(StructureQuery query);
    CompletionStage<StructureChangeResult> block(UUID groupId, RequestContext context);
    CompletionStage<StructureChangeResult> unblock(UUID groupId, RequestContext context);
    CompletionStage<OperationHandle> regenerate(UUID groupId, RequestContext context);
}

package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

public interface ProtectionChecks {
    long revision();
    CompletionStage<ProtectionBatchResult> check(MaintenanceAction action, Collection<ChunkKey> chunks);
    CompletionStage<ProtectionBatchResult> checkExternal(MaintenanceAction action, Collection<ChunkKey> chunks);
}

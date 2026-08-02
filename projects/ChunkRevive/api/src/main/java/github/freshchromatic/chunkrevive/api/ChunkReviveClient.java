package github.freshchromatic.chunkrevive.api;

import github.freshchromatic.chunkrevive.api.mark.MarkApi;
import github.freshchromatic.chunkrevive.api.operation.OperationApi;
import github.freshchromatic.chunkrevive.api.structure.StructureApi;

/** A client bound to one real Bukkit plugin consumer. */
public interface ChunkReviveClient {
    MarkApi marks();
    OperationApi operations();
    StructureApi structures();
}

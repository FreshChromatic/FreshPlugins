package github.freshchromatic.chunkrevive.api.integration;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Collection;
public record ProtectionQuery(MaintenanceAction action, Collection<ChunkKey> chunks) { public ProtectionQuery { chunks = ListCopy.copy(chunks); } }

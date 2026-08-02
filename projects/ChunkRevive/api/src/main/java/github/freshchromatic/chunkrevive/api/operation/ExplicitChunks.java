package github.freshchromatic.chunkrevive.api.operation;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Collection;
public record ExplicitChunks(Collection<ChunkKey> chunks) implements TargetSelection { public ExplicitChunks { chunks = ListCopy.copy(chunks); } }

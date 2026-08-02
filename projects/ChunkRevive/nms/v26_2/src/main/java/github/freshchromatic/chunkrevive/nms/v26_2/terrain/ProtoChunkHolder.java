package github.freshchromatic.chunkrevive.nms.v26_2.terrain;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal {@link GenerationChunkHolder} backed by a single {@link ChunkAccess}.
 *
 * <p>All methods in the Paper-patched parent that cast {@code this} to Moonrise's
 * {@code ChunkSystemChunkHolder} are overridden here without calling {@code super}
 * to avoid {@link ClassCastException}.
 */
final class ProtoChunkHolder extends GenerationChunkHolder {

    private final ChunkAccess chunk;

    ProtoChunkHolder(ChunkAccess chunk) {
        super(chunk.getPos());
        this.chunk = chunk;
    }

    @Override
    public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) { return chunk; }

    @Override
    public ChunkAccess getChunkIfPresent(ChunkStatus status) { return chunk; }

    @Override
    public ChunkAccess getLatestChunk() { return chunk; }

    @Override
    public ChunkStatus getPersistedStatus() { return chunk.getPersistedStatus(); }

    @Override
    public FullChunkStatus getFullStatus() { return FullChunkStatus.INACCESSIBLE; }

    @Override
    protected void addSaveDependency(CompletableFuture<?> future) {}

    @Override
    public int getTicketLevel() { return 0; }

    @Override
    public int getQueueLevel() { return 0; }
}

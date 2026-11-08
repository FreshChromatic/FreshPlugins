package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.api.operation.AllMarked;
import github.freshchromatic.chunkrevive.api.operation.ChunkRadius;
import github.freshchromatic.chunkrevive.api.operation.ExplicitChunks;
import github.freshchromatic.chunkrevive.api.operation.TargetSelection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;

import java.util.Collection;
import java.util.List;
import java.util.Set;

final class OperationTargets {

    private OperationTargets() {
    }

    static List<MarkedChunk> resolve(TargetSelection selection, Collection<MarkedChunk> all) {
        if (selection instanceof ExplicitChunks explicit) {
            Set<ChunkKey> wanted = Set.copyOf(explicit.chunks());
            return all.stream().filter(c -> wanted.contains(new ChunkKey(c.world(), c.cx(), c.cz()))).toList();
        }
        if (selection instanceof AllMarked marked) {
            return all.stream()
                .filter(c -> marked.world().map(c.world()::equals).orElse(true))
                .filter(
                chunk -> switch (marked.scope()) {
                    case ALL -> true;
                    case INDEPENDENT -> chunk.structureGroupId() == null;
                    case STRUCTURES -> chunk.structureGroupId() != null;
                })
                .toList();
        }
        if (selection instanceof ChunkRadius radius) {
            return all.stream()
                .filter(c -> c.world().equals(radius.world()) && Math.abs((long) c.cx()
                - radius.centerX()) <= radius.radius() && Math.abs((long) c.cz()
                    - radius.centerZ()) <= radius.radius())
                .toList();
        }
        return List.of();
    }

}

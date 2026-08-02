package github.freshchromatic.chunkrevive.feature.marking;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record MarkedChunk(String world, int cx, int cz, UUID markedBy, long markedAt,
                           @Nullable UUID structureGroupId, boolean biomeRegen) {

    public MarkedChunk(String world, int cx, int cz, UUID markedBy, long markedAt) {
        this(world, cx, cz, markedBy, markedAt, null, false);
    }

    public MarkedChunk(String world, int cx, int cz, UUID markedBy, long markedAt, @Nullable UUID structureGroupId) {
        this(world, cx, cz, markedBy, markedAt, structureGroupId, false);
    }

    public String coordDisplay() {
        return String.format("CX %,d / CZ %,d", cx, cz);
    }

    // Identity is purely positional — same world + coords = same chunk
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MarkedChunk other)) return false;
        return cx == other.cx && cz == other.cz && world.equals(other.world);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * world.hashCode() + cx) + cz;
    }
}

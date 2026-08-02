package github.freshchromatic.chunkrevive.api.structure;
import java.util.Optional;
public record StructureQuery(Optional<String> world, Optional<Boolean> blocked, int offset, int limit) {
    public StructureQuery {
        world = world == null ? Optional.empty() : world;
        blocked = blocked == null ? Optional.empty() : blocked;
        offset = Math.max(0, offset);
        limit = Math.clamp(limit, 1, 200);
    }
    public static StructureQuery all() { return new StructureQuery(Optional.empty(), Optional.empty(), 0, 50); }
}

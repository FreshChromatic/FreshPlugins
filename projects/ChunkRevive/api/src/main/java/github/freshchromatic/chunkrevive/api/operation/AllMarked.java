package github.freshchromatic.chunkrevive.api.operation;
import java.util.Optional;
public record AllMarked(Optional<String> world, MarkScope scope) implements TargetSelection {
    public AllMarked {
        world = world == null ? Optional.empty() : world;
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
    }
}

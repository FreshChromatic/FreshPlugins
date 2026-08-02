package github.freshchromatic.chunkrevive.api.event;
import github.freshchromatic.chunkrevive.api.mark.MarkedChunkSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
public final class ChunkMarkAddedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MarkedChunkSnapshot mark;
    private final String sourcePlugin;
    private final Optional<UUID> actor;
    private final Optional<String> correlationId;

    public ChunkMarkAddedEvent(
            MarkedChunkSnapshot mark,
            String sourcePlugin,
            Optional<UUID> actor,
            Optional<String> correlationId) {
        this.mark = mark;
        this.sourcePlugin = sourcePlugin;
        this.actor = actor;
        this.correlationId = correlationId;
    }

    public MarkedChunkSnapshot mark() { return mark; }
    public String sourcePlugin() { return sourcePlugin; }
    public Optional<UUID> actor() { return actor; }
    public Optional<String> correlationId() { return correlationId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

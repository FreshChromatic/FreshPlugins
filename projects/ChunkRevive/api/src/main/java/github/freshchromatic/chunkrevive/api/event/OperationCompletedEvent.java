package github.freshchromatic.chunkrevive.api.event;

import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Terminal notification for a unified operation; reliable integrations should also retain the ID. */
public final class OperationCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final OperationSnapshot operation;
    private final String sourcePlugin;
    public OperationCompletedEvent(OperationSnapshot operation, String sourcePlugin) { this.operation = operation; this.sourcePlugin = sourcePlugin; }
    public OperationSnapshot operation() { return operation; }
    public String sourcePlugin() { return sourcePlugin; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

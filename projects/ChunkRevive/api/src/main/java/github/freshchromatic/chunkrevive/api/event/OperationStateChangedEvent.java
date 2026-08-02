package github.freshchromatic.chunkrevive.api.event;

import github.freshchromatic.chunkrevive.api.operation.OperationSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Notification emitted after a unified operation changes state; it is never cancellable. */
public final class OperationStateChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final OperationSnapshot operation;
    private final String sourcePlugin;
    public OperationStateChangedEvent(OperationSnapshot operation, String sourcePlugin) { this.operation = operation; this.sourcePlugin = sourcePlugin; }
    public OperationSnapshot operation() { return operation; }
    public String sourcePlugin() { return sourcePlugin; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

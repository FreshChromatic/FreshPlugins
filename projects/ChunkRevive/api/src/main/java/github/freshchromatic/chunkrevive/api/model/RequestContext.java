package github.freshchromatic.chunkrevive.api.model;

import java.util.Optional;
import java.util.UUID;

public record RequestContext(Optional<UUID> actor, Optional<String> correlationId) {
    public RequestContext {
        actor = actor == null ? Optional.empty() : actor;
        correlationId = correlationId == null ? Optional.empty() : correlationId;
    }
    public static RequestContext system() { return new RequestContext(Optional.empty(), Optional.empty()); }
    public static RequestContext player(UUID playerId) { return new RequestContext(Optional.of(playerId), Optional.empty()); }
    public RequestContext withCorrelationId(String value) { return new RequestContext(actor, Optional.of(value)); }
}

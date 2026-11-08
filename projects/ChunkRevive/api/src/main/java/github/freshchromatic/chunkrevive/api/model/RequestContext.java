package github.freshchromatic.chunkrevive.api.model;

import java.util.Optional;
import java.util.UUID;

public record RequestContext(Optional<UUID> actor, Optional<String> correlationId) {

    public RequestContext {
        actor = actor == null ? Optional.empty() : actor;
        correlationId = correlationId == null ? Optional.empty() : correlationId;
        correlationId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("correlationId must not be blank");
            }
        });
    }

    public static RequestContext system() {
        return new RequestContext(Optional.empty(), Optional.empty());
    }

    public static RequestContext player(UUID playerId) {
        return new RequestContext(Optional.of(playerId), Optional.empty());
    }

    public RequestContext withCorrelationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        return new RequestContext(actor, Optional.of(value));
    }
}

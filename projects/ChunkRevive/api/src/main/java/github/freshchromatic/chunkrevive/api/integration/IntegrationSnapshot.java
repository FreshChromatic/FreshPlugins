package github.freshchromatic.chunkrevive.api.integration;

import java.util.Optional;

public record IntegrationSnapshot(String id, String ownerPlugin, boolean active, Optional<String> message) {

    public IntegrationSnapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ownerPlugin == null || ownerPlugin.isBlank()) {
            throw new IllegalArgumentException("ownerPlugin must not be blank");
        }
        message = message == null ? Optional.empty() : message;
    }
}

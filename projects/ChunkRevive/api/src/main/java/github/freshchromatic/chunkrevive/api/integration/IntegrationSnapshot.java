package github.freshchromatic.chunkrevive.api.integration;
import java.util.Optional;
public record IntegrationSnapshot(String id, String ownerPlugin, boolean active, Optional<String> message) { }

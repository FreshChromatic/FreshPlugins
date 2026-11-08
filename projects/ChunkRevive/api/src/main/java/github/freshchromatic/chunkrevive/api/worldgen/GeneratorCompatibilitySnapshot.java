package github.freshchromatic.chunkrevive.api.worldgen;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;

public record GeneratorCompatibilitySnapshot(String world, String activeGeneratorClass,
    GeneratorSupportLevel supportLevel, Set<GeneratorCapability> capabilities, Optional<String> integrationId,
    Optional<String> integrationVersion, Optional<String> fingerprint, Optional<String> reasonCode,
    List<String> warnings) {

    public GeneratorCompatibilitySnapshot {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        Objects.requireNonNull(activeGeneratorClass, "activeGeneratorClass");
        Objects.requireNonNull(supportLevel, "supportLevel");
        capabilities = Set.copyOf(capabilities);
        integrationId = integrationId == null ? Optional.empty() : integrationId;
        integrationVersion = integrationVersion == null ? Optional.empty() : integrationVersion;
        fingerprint = fingerprint == null ? Optional.empty() : fingerprint;
        reasonCode = reasonCode == null ? Optional.empty() : reasonCode;
        warnings = List.copyOf(warnings);
    }

    public boolean supports(GeneratorCapability capability) {
        return capabilities.contains(capability);
    }
}

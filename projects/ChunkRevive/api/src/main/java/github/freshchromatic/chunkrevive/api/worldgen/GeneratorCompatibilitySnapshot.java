package github.freshchromatic.chunkrevive.api.worldgen;
import java.util.List;
import java.util.Optional;
import java.util.Set;
public record GeneratorCompatibilitySnapshot(String world, String activeGeneratorClass, GeneratorSupportLevel supportLevel,
    Set<GeneratorCapability> capabilities, Optional<String> integrationId, Optional<String> integrationVersion,
    Optional<String> fingerprint, Optional<String> reasonCode, List<String> warnings) {
    public GeneratorCompatibilitySnapshot { capabilities = Set.copyOf(capabilities); warnings = List.copyOf(warnings); }
    public boolean supports(GeneratorCapability capability) { return capabilities.contains(capability); }
}

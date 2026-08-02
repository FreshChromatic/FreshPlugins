package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.Capabilities;
import github.freshchromatic.chunkrevive.api.operation.OperationType;
import java.util.Set;

final class DefaultCapabilities implements Capabilities {
    @Override public boolean supports(OperationType type) { return type == OperationType.REGENERATE || type == OperationType.RESET || type == OperationType.SCAN_EXISTING_CHUNKS; }
    @Override public boolean supportsIntegration(String integrationType) { return "protection".equalsIgnoreCase(integrationType); }
    @Override public Set<String> flags() { return Set.of("marks", "protection-providers", "generator-diagnostics"); }
}

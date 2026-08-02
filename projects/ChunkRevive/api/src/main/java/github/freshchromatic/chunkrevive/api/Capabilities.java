package github.freshchromatic.chunkrevive.api;

import github.freshchromatic.chunkrevive.api.operation.OperationType;
import java.util.Set;

public interface Capabilities {
    boolean supports(OperationType type);
    boolean supportsIntegration(String integrationType);
    Set<String> flags();
}

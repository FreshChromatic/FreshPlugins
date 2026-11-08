package github.freshchromatic.chunkrevive.api.operation;

import java.util.Map;

public record MaintenanceRequest(OperationType type, TargetSelection targets, Map<String, String> options) {

    public MaintenanceRequest {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (targets == null) {
            throw new IllegalArgumentException("targets must not be null");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static MaintenanceRequest regenerate(TargetSelection targets) {
        return new MaintenanceRequest(OperationType.REGENERATE, targets, Map.of());
    }

    public static MaintenanceRequest reset(TargetSelection targets) {
        return new MaintenanceRequest(OperationType.RESET, targets, Map.of());
    }
}

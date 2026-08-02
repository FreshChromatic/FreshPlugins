package github.freshchromatic.chunkrevive.api.integration;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import java.util.Map;
public record ProtectionBatchResult(Map<ChunkKey, ProtectionDecision> decisions, Map<ChunkKey, String> reasonCodes) {
    public ProtectionBatchResult {
        decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
        reasonCodes = reasonCodes == null ? Map.of() : Map.copyOf(reasonCodes);
    }
}

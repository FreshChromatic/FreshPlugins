package github.freshchromatic.chunkrevive.api.operation;
import java.time.Instant;
import java.util.List;
public record OperationPreview(PreviewToken token, Instant expiresAt, boolean executable, int requestedTargets,
    int acceptedTargets, int rejectedTargets, int regenerateChunks, int deleteChunks, int deleteRegions,
    List<PreviewRejection> rejections) { public OperationPreview { rejections = List.copyOf(rejections); } }

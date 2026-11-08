package github.freshchromatic.chunkrevive.api.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OperationPreview(PreviewToken token, Instant expiresAt, boolean executable, int requestedTargets,
    int acceptedTargets, int rejectedTargets, int regenerateChunks, int deleteChunks, int deleteRegions,
    List<PreviewRejection> rejections) {

    public OperationPreview {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        rejections = List.copyOf(rejections);
        if (requestedTargets < 0 || acceptedTargets < 0 || rejectedTargets < 0 || regenerateChunks < 0
            || deleteChunks < 0 || deleteRegions < 0) {
            throw new IllegalArgumentException("preview counts must not be negative");
        }
        if ((long) acceptedTargets + rejectedTargets != requestedTargets) {
            throw new IllegalArgumentException("accepted and rejected targets must equal requested targets");
        }
        if (requestedTargets > 0 && rejections.size() != rejectedTargets) {
            throw new IllegalArgumentException("rejection details must match the number of rejected targets");
        }
        if (executable && (acceptedTargets == 0 || rejectedTargets > 0 || !rejections.isEmpty())) {
            throw new IllegalArgumentException("an executable preview must include only accepted targets");
        }
    }
}

package github.freshchromatic.chunkrevive.api.mark;

import java.util.List;

public record BulkMarkResult(int requested, int changed, int unchanged, int rejected, List<MarkChangeResult> results) {

    public BulkMarkResult {
        results = List.copyOf(results);
        if (requested < 0 || changed < 0 || unchanged < 0 || rejected < 0) {
            throw new IllegalArgumentException("bulk mark counts must not be negative");
        }
        if ((long) changed + unchanged + rejected != requested || results.size() != requested) {
            throw new IllegalArgumentException("bulk mark counts must match the requested results");
        }
        int actualChanged = 0;
        int actualRejected = 0;
        for (MarkChangeResult result : results) {
            switch (result.status()) {
                case ADDED, REMOVED -> actualChanged++;
                case WORLD_NOT_ALLOWED, PROTECTION_BLOCKED, WORLD_NOT_FOUND -> actualRejected++;
                case ALREADY_MARKED, NOT_MARKED -> {
                }
            }
        }
        if (changed != actualChanged || rejected != actualRejected || unchanged != requested - actualChanged
            - actualRejected) {
            throw new IllegalArgumentException("bulk mark counts must match their result statuses");
        }
    }
}

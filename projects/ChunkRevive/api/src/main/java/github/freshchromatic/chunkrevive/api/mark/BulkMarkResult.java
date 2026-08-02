package github.freshchromatic.chunkrevive.api.mark;
import java.util.List;
public record BulkMarkResult(int requested, int changed, int unchanged, int rejected, List<MarkChangeResult> results) {
    public BulkMarkResult { results = List.copyOf(results); }
}

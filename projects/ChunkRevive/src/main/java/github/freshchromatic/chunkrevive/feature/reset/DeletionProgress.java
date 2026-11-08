package github.freshchromatic.chunkrevive.feature.reset;

import java.util.ArrayList;
import java.util.List;

final class DeletionProgress {

    record Amount(long count, long bytes) {}

    record Update(List<Amount> reports, Amount total) {}

    private int     pendingCount;
    private long    pendingBytes;
    private long    totalCount;
    private long    totalBytes;
    private boolean reported;

    List<Amount> record(int completed, long releasedBytes, int interval) {
        pendingCount += completed;
        pendingBytes += releasedBytes;
        totalCount += completed;
        totalBytes += releasedBytes;
        int reportEvery = Math.max(1, interval);
        List<Amount> reports = new ArrayList<>();
        while (pendingCount >= reportEvery) {
            long reportBytes = pendingBytes <= 0L || reportEvery >= pendingCount ? pendingBytes
                : Math.round((double) pendingBytes * reportEvery / pendingCount);
            reports.add(new Amount(reportEvery, reportBytes));
            reported = true;
            pendingCount -= reportEvery;
            pendingBytes -= reportBytes;
        }
        return List.copyOf(reports);
    }

    Update finish() {
        List<Amount> reports = reported && pendingCount > 0 ? List.of(new Amount(pendingCount, pendingBytes))
            : List.of();
        Amount total = new Amount(totalCount, totalBytes);
        reset();
        return new Update(reports, total);
    }

    void reset() {
        pendingCount = 0;
        pendingBytes = 0L;
        totalCount = 0L;
        totalBytes = 0L;
        reported = false;
    }
}

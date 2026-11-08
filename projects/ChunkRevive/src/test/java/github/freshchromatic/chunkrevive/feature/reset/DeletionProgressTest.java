package github.freshchromatic.chunkrevive.feature.reset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeletionProgressTest {

    @Test
    void reportsIntervalsAndRemainderWithTotal() {
        var progress = new DeletionProgress();
        assertEquals(List.of(), progress.record(3, 30, 5));
        assertEquals(List.of(new DeletionProgress.Amount(5, 50), new DeletionProgress.Amount(5, 50)), progress.record(9,
            90, 5));
        var update = progress.finish();
        assertEquals(List.of(new DeletionProgress.Amount(2, 20)), update.reports());
        assertEquals(new DeletionProgress.Amount(12, 120), update.total());
    }

    @Test
    void smallFinishedBatchOnlyReportsTotalAndResets() {
        var progress = new DeletionProgress();
        assertEquals(List.of(), progress.record(2, 17, 5));
        var first = progress.finish();
        assertEquals(List.of(), first.reports());
        assertEquals(new DeletionProgress.Amount(2, 17), first.total());
        progress.record(1, 3, 5);
        assertEquals(new DeletionProgress.Amount(1, 3), progress.finish().total());
    }

    @Test
    void resetDiscardsPendingTotals() {
        var progress = new DeletionProgress();
        progress.record(3, 11, 5);
        progress.reset();
        progress.record(1, 4, 5);
        assertEquals(new DeletionProgress.Amount(1, 4), progress.finish().total());
    }

    @Test
    void distributesRoundedBytesWithoutLosingTheRemainder() {
        var progress = new DeletionProgress();
        assertEquals(List.of(new DeletionProgress.Amount(2, 7)), progress.record(3, 10, 2));
        var update = progress.finish();
        assertEquals(List.of(new DeletionProgress.Amount(1, 3)), update.reports());
        assertEquals(new DeletionProgress.Amount(3, 10), update.total());
    }

    @Test
    void nonPositiveIntervalReportsEachCompletedItem() {
        var progress = new DeletionProgress();
        assertEquals(List.of(new DeletionProgress.Amount(1, 3), new DeletionProgress.Amount(1, 2)), progress.record(2,
            5, 0));
        assertEquals(new DeletionProgress.Amount(2, 5), progress.finish().total());
    }
}

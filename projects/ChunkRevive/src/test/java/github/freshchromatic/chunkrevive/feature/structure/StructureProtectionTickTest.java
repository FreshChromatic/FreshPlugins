package github.freshchromatic.chunkrevive.feature.structure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureProtectionTickTest {

    @Test
    void waitsForEveryPlayerAndDeduplicatesActiveGroups() {
        UUID sharedGroup = UUID.randomUUID();
        UUID firstGroup = UUID.randomUUID();
        UUID secondGroup = UUID.randomUUID();
        List<StructureProtectionTick.Result> completedScans = new ArrayList<>();
        var scan = new StructureProtectionTick(3, completedScans::add);

        scan.playerCompleted(Set.of(sharedGroup, firstGroup));
        scan.playerCompleted(Set.of(sharedGroup, secondGroup));

        assertTrue(completedScans.isEmpty());

        scan.playerCompleted(Set.of());

        assertEquals(List.of(new StructureProtectionTick.Result(Set.of(sharedGroup, firstGroup, secondGroup), true)),
            completedScans);
    }

    @Test
    void completesAfterConcurrentPlayerScans() throws InterruptedException {
        int playerCount = 8;
        CountDownLatch completed = new CountDownLatch(1);
        List<StructureProtectionTick.Result> completedScans = new ArrayList<>();
        var scan = new StructureProtectionTick(playerCount, activeGroups -> {
            completedScans.add(activeGroups);
            completed.countDown();
        });
        UUID groupId = UUID.randomUUID();

        var threads = new ArrayList<Thread>();
        for (int index = 0; index < playerCount; index++) {
            threads.add(new Thread(() -> scan.playerCompleted(Set.of(groupId))));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(new StructureProtectionTick.Result(Set.of(groupId), true)), completedScans);
    }

    @Test
    void emptyPlayerScanCompletesImmediately() {
        List<StructureProtectionTick.Result> completedScans = new ArrayList<>();

        new StructureProtectionTick(0, completedScans::add);

        assertEquals(List.of(new StructureProtectionTick.Result(Set.of(), true)), completedScans);
    }

    @Test
    void failedPlayerScanMarksTheCycleIncomplete() {
        List<StructureProtectionTick.Result> completedScans = new ArrayList<>();
        var scan = new StructureProtectionTick(2, completedScans::add);
        UUID groupId = UUID.randomUUID();

        scan.playerFailed(Set.of());
        scan.playerCompleted(Set.of(groupId));

        assertEquals(List.of(new StructureProtectionTick.Result(Set.of(groupId), false)), completedScans);
    }
}

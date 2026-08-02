package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeletionJobPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void unfinishedDeletionJobsSurviveRepositoryReopen() {
        Path file = directory.resolve("chunkrevive.db");
        UUID waitingId = UUID.randomUUID();
        UUID runningId = UUID.randomUUID();

        var firstDatabase = new SqliteDatabase(file.toString());
        assertTrue(firstDatabase.connect());
        var firstRepository = new PersistenceRepository(firstDatabase);
        firstRepository.init();
        firstRepository.saveDeletionJobs(List.of(
            new DeletionJob(waitingId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 12, -9, 100L),
            new DeletionJob(runningId, "REGION_PRUNE", "RUNNING", "world_nether", -2, 3, 200L)));
        firstDatabase.close();

        var secondDatabase = new SqliteDatabase(file.toString());
        assertTrue(secondDatabase.connect());
        var secondRepository = new PersistenceRepository(secondDatabase);
        secondRepository.init();
        var restored = secondRepository.loadDeletionJobs();
        assertEquals(2, restored.size());
        assertEquals(waitingId, restored.get(0).id());
        assertEquals("WAITING_FOR_COLD", restored.get(0).state());
        assertEquals(runningId, restored.get(1).id());
        assertEquals("RUNNING", restored.get(1).state());

        secondRepository.updateDeletionJobState(runningId, "WAITING_FOR_COLD");
        secondRepository.deleteDeletionJob(waitingId);
        restored = secondRepository.loadDeletionJobs();
        assertEquals(1, restored.size());
        assertEquals("WAITING_FOR_COLD", restored.getFirst().state());

        secondRepository.deleteAllDeletionJobs();
        assertTrue(secondRepository.loadDeletionJobs().isEmpty());
        secondDatabase.close();
    }

    @Test
    void deletionJobsCanBeUpdatedAndDeletedInBatches() {
        Path file = directory.resolve("chunkrevive-batch.db");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        var database = new SqliteDatabase(file.toString());
        assertTrue(database.connect());
        var repository = new PersistenceRepository(database);
        repository.init();
        repository.saveDeletionJobs(List.of(
            new DeletionJob(firstId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 100L),
            new DeletionJob(secondId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 3, 4, 200L)));

        repository.updateDeletionJobStates(List.of(firstId, secondId), "RUNNING");
        assertTrue(repository.loadDeletionJobs().stream().allMatch(job -> job.state().equals("RUNNING")));

        repository.deleteDeletionJobs(List.of(firstId, secondId));
        assertTrue(repository.loadDeletionJobs().isEmpty());
        database.close();
    }
}

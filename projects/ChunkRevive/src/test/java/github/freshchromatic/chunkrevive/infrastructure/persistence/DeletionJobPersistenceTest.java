package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.feature.reset.DeletionJob;
import github.freshchromatic.chunkrevive.feature.operation.OperationRecord;
import github.freshchromatic.freshlib.database.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        firstRepository.saveDeletionJobs(List.of(new DeletionJob(waitingId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world",
            12, -9, 100L), new DeletionJob(runningId, "REGION_PRUNE", "RUNNING", "world_nether", -2, 3, 200L)));
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
        repository.saveDeletionJobs(List.of(new DeletionJob(firstId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2,
            100L), new DeletionJob(secondId, "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 3, 4, 200L)));

        repository.updateDeletionJobStates(List.of(firstId, secondId), "RUNNING");
        assertTrue(repository.loadDeletionJobs().stream().allMatch(job -> job.state().equals("RUNNING")));

        repository.deleteDeletionJobs(List.of(firstId, secondId));
        assertTrue(repository.loadDeletionJobs().isEmpty());
        database.close();
    }

    @Test
    void missingDeletionTableReportsLoadFailure() {
        var database = new SqliteDatabase(directory.resolve("missing-table.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            assertThrows(IllegalStateException.class, repository::loadDeletionJobs);
        } finally {
            database.close();
        }
    }

    @Test
    void missingDeletionTableReportsDeleteFailure() {
        var database = new SqliteDatabase(directory.resolve("delete-missing-table.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            assertThrows(IllegalStateException.class, () -> repository.deleteDeletionJob(UUID.randomUUID()));
        } finally {
            database.close();
        }
    }

    @Test
    void failedBatchSaveRollsBackAndReportsFailure() {
        var database = new SqliteDatabase(directory.resolve("duplicate-targets.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var first = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 100);
            var duplicate = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 101);

            assertThrows(IllegalStateException.class, () -> repository.saveDeletionJobs(List.of(first, duplicate)));
            assertTrue(repository.loadDeletionJobs().isEmpty());
        } finally {
            database.close();
        }
    }

    @Test
    void failedBatchSaveRollsBackOperationAssociations() {
        var database = new SqliteDatabase(directory.resolve("duplicate-associated-targets.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            UUID operationId = UUID.randomUUID();
            var first = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 100);
            var duplicate = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 101);
            repository.save(new OperationRecord(operationId, "RESET", "RUNNING", 0, 1, 100, 100, "test", null, null));

            assertThrows(IllegalStateException.class, () -> repository.saveDeletionJobs(List.of(first, duplicate),
                operationId));

            assertTrue(repository.loadDeletionJobs().isEmpty());
            assertEquals(List.of(), repository.loadOperations().getFirst().deletionJobIds());
        } finally {
            database.close();
        }
    }

    @Test
    void operationBatchAssociatesNewAndExistingJobsAtomically() {
        var database = new SqliteDatabase(directory.resolve("operation-batch.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            UUID operationId = UUID.randomUUID();
            var existing = new DeletionJob(UUID.randomUUID(), "REGION_PRUNE", "WAITING_FOR_COLD", "world", 0, 0, 100);
            var added = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 4, 5, 101);
            repository.save(new OperationRecord(operationId, "RESET", "RUNNING", 0, 2, 100, 100, "test", null, null));
            repository.saveDeletionJobs(List.of(existing));

            repository.saveDeletionJobs(List.of(added), operationId, List.of(existing.id()));

            assertEquals(Set.of(existing.id(), added.id()), Set.copyOf(
                repository.loadOperations().getFirst().deletionJobIds()));
        } finally {
            database.close();
        }
    }

    @Test
    void failedOperationBatchRollsBackNewJobsAndExistingJobAssociations() {
        var database = new SqliteDatabase(directory.resolve("operation-batch-rollback.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            UUID operationId = UUID.randomUUID();
            var existing = new DeletionJob(UUID.randomUUID(), "REGION_PRUNE", "WAITING_FOR_COLD", "world", 0, 0, 100);
            var added = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 4, 5, 101);
            var duplicate = new DeletionJob(added.id(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 6, 7, 102);
            repository.save(new OperationRecord(operationId, "RESET", "RUNNING", 0, 2, 100, 100, "test", null, null));
            repository.saveDeletionJobs(List.of(existing));

            assertThrows(IllegalStateException.class, () -> repository.saveDeletionJobs(List.of(added, duplicate),
                operationId, List.of(existing.id())));

            assertEquals(List.of(existing.id()), repository.loadDeletionJobs().stream().map(DeletionJob::id).toList());
            assertEquals(List.of(), repository.loadOperations().getFirst().deletionJobIds());
        } finally {
            database.close();
        }
    }

    @Test
    void missingDeletionTableReportsClearFailure() {
        var database = new SqliteDatabase(directory.resolve("clear-missing-table.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            assertThrows(IllegalStateException.class, repository::deleteAllDeletionJobs);
        } finally {
            database.close();
        }
    }

    @Test
    void missingDeletionTableReportsStateUpdateFailure() {
        var database = new SqliteDatabase(directory.resolve("update-missing-table.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            assertThrows(IllegalStateException.class, () -> repository.updateDeletionJobState(UUID.randomUUID(),
                "RUNNING"));
        } finally {
            database.close();
        }
    }

    @Test
    void stateUpdateRollsBackWhenAnyJobIsMissing() {
        var database = new SqliteDatabase(directory.resolve("missing-job.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var existing = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "WAITING_FOR_COLD", "world", 1, 2, 100);
            repository.saveDeletionJobs(List.of(existing));

            assertThrows(IllegalStateException.class, () -> repository.updateDeletionJobStates(List.of(existing.id(),
                UUID.randomUUID()), "RUNNING"));

            assertEquals("WAITING_FOR_COLD", repository.loadDeletionJobs().getFirst().state());
        } finally {
            database.close();
        }
    }

    @Test
    void repeatingDeletionJobStateUpdateSucceeds() {
        var database = new SqliteDatabase(directory.resolve("unchanged-job-state.db").toString());
        assertTrue(database.connect());
        try {
            var repository = new PersistenceRepository(database);
            repository.init();
            var job = new DeletionJob(UUID.randomUUID(), "CHUNK_DELETE", "RUNNING", "world", 1, 2, 100);
            repository.saveDeletionJobs(List.of(job));

            repository.updateDeletionJobState(job.id(), "RUNNING");

            assertEquals("RUNNING", repository.loadDeletionJobs().getFirst().state());
        } finally {
            database.close();
        }
    }
}

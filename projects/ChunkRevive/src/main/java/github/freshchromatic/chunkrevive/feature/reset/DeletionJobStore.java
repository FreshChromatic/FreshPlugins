package github.freshchromatic.chunkrevive.feature.reset;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence port for online deletion jobs. */
public interface DeletionJobStore {
    void saveDeletionJobs(Collection<DeletionJob> jobs);
    void updateDeletionJobState(UUID id, String state);
    void updateDeletionJobStates(Collection<UUID> ids, String state);
    void deleteDeletionJob(UUID id);
    void deleteDeletionJobs(Collection<UUID> ids);
    void deleteAllDeletionJobs();
    List<DeletionJob> loadDeletionJobs();
}

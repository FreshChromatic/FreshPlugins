package github.freshchromatic.chunkrevive.feature.structure;

import java.util.List;
import java.util.UUID;

/** Persistence port for structure groups. */
public interface StructureStore {
    void upsertGroup(StructureGroup group);
    void updateProtection(UUID groupId, long protectionTicks, boolean blocked);
    void updateNextRefresh(UUID groupId, long nextRefreshAt);
    List<StructureGroup> loadAllGroups();
}

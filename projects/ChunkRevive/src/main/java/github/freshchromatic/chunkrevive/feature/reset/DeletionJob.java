package github.freshchromatic.chunkrevive.feature.reset;

import java.util.UUID;

public record DeletionJob(UUID id, String type, String state, String world,
                                int x, int z, long createdAt) {}

package github.freshchromatic.chunkrevive.nms;

import java.util.List;

public record StoredChunk(ChunkCoordinate coordinate, List<StructureInfo> structures) {}

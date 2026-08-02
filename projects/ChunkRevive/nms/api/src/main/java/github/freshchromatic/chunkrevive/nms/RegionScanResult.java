package github.freshchromatic.chunkrevive.nms;

import java.util.List;

public record RegionScanResult(List<StoredChunk> chunks, int failedReads) {}

package github.freshchromatic.chunkrevive.nms;

/** An Anvil region with reclaimable trailing sectors in at least one empty storage file. */
public record EmptyRegionInfo(int regionX, int regionZ, long reclaimableBytes) {}

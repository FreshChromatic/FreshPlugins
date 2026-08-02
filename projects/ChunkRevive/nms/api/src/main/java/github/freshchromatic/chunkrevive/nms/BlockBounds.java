package github.freshchromatic.chunkrevive.nms;

/** Inclusive block-space bounds independent from Minecraft's BoundingBox class. */
public record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

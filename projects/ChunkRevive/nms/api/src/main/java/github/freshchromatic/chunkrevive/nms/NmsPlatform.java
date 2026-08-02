package github.freshchromatic.chunkrevive.nms;

import java.util.Set;

/** Entry point contributed by every shaded version adapter. */
public interface NmsPlatform {
    Set<String> supportedMinecraftVersions();

    TerrainGateway terrain();

    WorldInspectionGateway worldInspection();

    ChunkStorageGateway chunkStorage();

    WorldScanGateway worldScan();
}

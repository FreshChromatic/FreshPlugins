package github.freshchromatic.chunkrevive.nms.v26_1_2;

import github.freshchromatic.chunkrevive.nms.NmsPlatform;
import github.freshchromatic.chunkrevive.nms.TerrainGateway;
import github.freshchromatic.chunkrevive.nms.v26_1_2.terrain.V26_1_2TerrainGateway;
import github.freshchromatic.chunkrevive.nms.WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.v26_1_2.inspection.V26_1_2WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.v26_1_2.storage.V26_1_2ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.chunkrevive.nms.v26_1_2.scan.V26_1_2WorldScanGateway;

import java.util.Set;

public final class V26_1_2NmsPlatform implements NmsPlatform {
    private final TerrainGateway terrain = new V26_1_2TerrainGateway();
    private final WorldInspectionGateway worldInspection = new V26_1_2WorldInspectionGateway();
    private final ChunkStorageGateway chunkStorage = new V26_1_2ChunkStorageGateway();
    private final WorldScanGateway worldScan = new V26_1_2WorldScanGateway();

    @Override
    public Set<String> supportedMinecraftVersions() {
        return Set.of("26.1.2");
    }

    @Override
    public TerrainGateway terrain() {
        return terrain;
    }

    @Override
    public WorldInspectionGateway worldInspection() {
        return worldInspection;
    }

    @Override
    public ChunkStorageGateway chunkStorage() {
        return chunkStorage;
    }

    @Override
    public WorldScanGateway worldScan() {
        return worldScan;
    }
}

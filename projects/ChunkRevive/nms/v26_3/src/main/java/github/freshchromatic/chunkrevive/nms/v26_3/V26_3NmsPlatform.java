package github.freshchromatic.chunkrevive.nms.v26_3;

import github.freshchromatic.chunkrevive.nms.NmsPlatform;
import github.freshchromatic.chunkrevive.nms.TerrainGateway;
import github.freshchromatic.chunkrevive.nms.v26_3.terrain.V26_3TerrainGateway;
import github.freshchromatic.chunkrevive.nms.WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.v26_3.inspection.V26_3WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.v26_3.storage.V26_3ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.chunkrevive.nms.v26_3.scan.V26_3WorldScanGateway;

import java.util.Set;

public final class V26_3NmsPlatform implements NmsPlatform {
    private final TerrainGateway terrain = new V26_3TerrainGateway();
    private final WorldInspectionGateway worldInspection = new V26_3WorldInspectionGateway();
    private final ChunkStorageGateway chunkStorage = new V26_3ChunkStorageGateway();
    private final WorldScanGateway worldScan = new V26_3WorldScanGateway();

    @Override
    public Set<String> supportedMinecraftVersions() {
        return Set.of("26.3");
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

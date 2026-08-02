package github.freshchromatic.chunkrevive.nms.v1_21_11;

import github.freshchromatic.chunkrevive.nms.NmsPlatform;
import github.freshchromatic.chunkrevive.nms.TerrainGateway;
import github.freshchromatic.chunkrevive.nms.v1_21_11.terrain.V1_21_11TerrainGateway;
import github.freshchromatic.chunkrevive.nms.WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.v1_21_11.inspection.V1_21_11WorldInspectionGateway;
import github.freshchromatic.chunkrevive.nms.ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.v1_21_11.storage.V1_21_11ChunkStorageGateway;
import github.freshchromatic.chunkrevive.nms.WorldScanGateway;
import github.freshchromatic.chunkrevive.nms.v1_21_11.scan.V1_21_11WorldScanGateway;

import java.util.Set;

public final class V1_21_11NmsPlatform implements NmsPlatform {
    private final TerrainGateway terrain = new V1_21_11TerrainGateway();
    private final WorldInspectionGateway worldInspection = new V1_21_11WorldInspectionGateway();
    private final ChunkStorageGateway chunkStorage = new V1_21_11ChunkStorageGateway();
    private final WorldScanGateway worldScan = new V1_21_11WorldScanGateway();

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



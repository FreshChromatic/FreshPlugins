package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.nms.BlockBounds;
import github.freshchromatic.chunkrevive.nms.NmsPlatformLoader;
import github.freshchromatic.chunkrevive.nms.WorldInspectionGateway;
import org.bukkit.World;
import java.util.List;

/** Structure presence detection, adapted from ResidenceTransfer's StructureHUD scanning logic. */
public final class StructureDetector {

    public record Detected(String structureId, BlockBounds boundingBox) {}

    private PluginConfig config;
    private final WorldInspectionGateway inspection;

    public StructureDetector(PluginConfig config) {
        this.config = config;
        this.inspection = NmsPlatformLoader.load().worldInspection();
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * Returns all valid StructureStarts in the player's current chunk (and, if
     * {@code scan-radius-chunks} > 0, surrounding chunks) that pass the
     * structure.refresh list-mode filter. Structures that fail the filter are
     * treated as if they don't exist for any caller of this method.
     */
    public List<Detected> detectAt(World world, int blockX, int blockZ) {
        int radius = Math.max(0, config.structure.detect.scanRadiusChunks);
        return inspection.detectStructures(
                world, blockX, blockZ, radius, config.structure.refresh::isTracked)
            .stream()
            .map(info -> new Detected(info.id(), info.bounds()))
            .toList();
    }
}

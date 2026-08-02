package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.nms.BlockBounds;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Expands a single chunk mark to the whole structure's range, when applicable. */
public final class StructureMarkExpander {

    private PluginConfig config;
    private final StructureDetector structureDetector;
    private final StructureRegistry structureRegistry;
    private final LandProtection landProtection;

    public StructureMarkExpander(PluginConfig config, StructureDetector structureDetector,
                                  StructureRegistry structureRegistry, LandProtection landProtection) {
        this.config = config;
        this.structureDetector = structureDetector;
        this.structureRegistry = structureRegistry;
        this.landProtection = landProtection;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /** Returns the chunks that should end up marked; empty means "blocked by Residence". */
    public List<MarkedChunk> expand(World world, int cx, int cz, UUID markedBy) {
        if (!config.structure.enabled || !config.structure.mark.expandToFullStructure) {
            return plainMark(world, cx, cz, markedBy);
        }

        for (var detected : structureDetector.detectAt(world, cx * 16 + 8, cz * 16 + 8)) {
            BlockBounds bb = detected.boundingBox();
            int minCx = bb.minX() >> 4, maxCx = bb.maxX() >> 4;
            int minCz = bb.minZ() >> 4, maxCz = bb.maxZ() >> 4;

            List<int[]> effective = structureRegistry.resolveEffectiveChunks(
                world.getName(), minCx, maxCx, minCz, maxCz);
            if (effective == null || effective.isEmpty()) {
                return plainMark(world, cx, cz, markedBy);
            }

            UUID groupId = structureRegistry.findOrCreateGroup(
                world.getName(), detected.structureId(), minCx, maxCx, minCz, maxCz);

            long now = System.currentTimeMillis();
            List<MarkedChunk> chunks = new ArrayList<>();
            for (int[] coord : effective) {
                chunks.add(new MarkedChunk(world.getName(), coord[0], coord[1], markedBy, now, groupId));
            }
            return chunks;
        }

        return plainMark(world, cx, cz, markedBy);
    }

    private List<MarkedChunk> plainMark(World world, int cx, int cz, UUID markedBy) {
        if (landProtection.hasClaim(world, cx, cz)) return List.of();
        return List.of(new MarkedChunk(world.getName(), cx, cz, markedBy, System.currentTimeMillis()));
    }
}

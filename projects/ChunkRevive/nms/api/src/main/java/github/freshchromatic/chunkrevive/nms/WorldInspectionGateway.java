package github.freshchromatic.chunkrevive.nms;

import org.bukkit.World;

import java.util.List;
import java.util.function.Predicate;

/** Version-specific access to live world-generation metadata. */
public interface WorldInspectionGateway {
    List<StructureInfo> detectStructures(
        World world, int blockX, int blockZ, int radiusChunks, Predicate<String> idFilter);
}

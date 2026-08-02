package github.freshchromatic.chunkrevive.nms.v26_2.inspection;

import github.freshchromatic.chunkrevive.nms.BlockBounds;
import github.freshchromatic.chunkrevive.nms.StructureInfo;
import github.freshchromatic.chunkrevive.nms.WorldInspectionGateway;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class V26_2WorldInspectionGateway implements WorldInspectionGateway {
    @Override
    public List<StructureInfo> detectStructures(World world, int blockX, int blockZ,
                                                int radiusChunks, Predicate<String> idFilter) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        ChunkPos chunkPos = new ChunkPos(blockX >> 4, blockZ >> 4);
        List<StructureInfo> result = new ArrayList<>();
        Set<StructureStart> seen = new HashSet<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                ChunkPos inspected = new ChunkPos(chunkPos.x() + dx, chunkPos.z() + dz);
                for (StructureStart start : level.structureManager().startsForStructure(
                        inspected, structure -> {
                            String id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                                .getKey(structure).toString();
                            return idFilter.test(id);
                        })) {
                    if (!start.isValid() || !seen.add(start)) continue;
                    String id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                        .getKey(start.getStructure()).toString();
                    var box = start.getBoundingBox();
                    result.add(new StructureInfo(id, new BlockBounds(
                        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())));
                }
            }
        }
        return result;
    }
}

package github.freshchromatic.chunkrevive.nms.v26_2.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.List;
import github.freshchromatic.chunkrevive.nms.EmptyRegionInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OnlineRegionTruncationTest {

    @TempDir
    Path directory;

    @Test
    void emptyRegionCanBeTruncatedAndReusedWhileOriginalHandleStaysOpen() throws Exception {
        Path path = directory.resolve("r.0.0.mca");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunkrevive-test");
        ChunkPos pos = new ChunkPos(0, 0);

        try (RegionFile region = new RegionFile(info, path, directory, false)) {
            CompoundTag first = new CompoundTag();
            byte[] incompressible = new byte[64 * 1024];
            new Random(42L).nextBytes(incompressible);
            first.putByteArray("payload", incompressible);
            try (DataOutputStream output = region.getChunkDataOutputStream(pos)) {
                NbtIo.write(first, output);
            }
            region.flush();

            long populatedSize = Files.size(path);
            assertTrue(populatedSize > 8192L);
            long allocatedDataBytes = Math.ceilDiv(populatedSize - 8192L, 4096L) * 4096L;
            assertEquals(allocatedDataBytes,
                V26_2ChunkStorageGateway.allocatedSectorBytes(path, List.of(pos)),
                "allocated sectors should be reported even though clearing does not shrink the file");

            region.clear(pos);
            assertFalse(region.hasChunk(pos));
            assertEquals(populatedSize, Files.size(path), "RegionFile.clear should free sectors without shrinking the file");

            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(8192L);
                channel.force(true);
            }
            assertEquals(8192L, Files.size(path));

            CompoundTag second = new CompoundTag();
            second.putString("marker", "regenerated");
            try (DataOutputStream output = region.getChunkDataOutputStream(pos)) {
                NbtIo.write(second, output);
            }
            region.flush();
            assertTrue(region.hasChunk(pos));
            try (DataInputStream input = region.getChunkDataInputStream(pos)) {
                assertEquals("regenerated", NbtIo.read(input).getString("marker").orElseThrow());
            }
        }
    }

    @Test
    void scanFindsOnlyOversizedFilesWithEmptyLocationTablesAndAggregatesStores() throws Exception {
        Path terrain = Files.createDirectories(directory.resolve("region"));
        Path entities = Files.createDirectories(directory.resolve("entities"));
        Files.createDirectories(directory.resolve("poi"));

        Files.write(terrain.resolve("r.2.-3.mca"), new byte[16 * 1024]);
        Files.write(entities.resolve("r.2.-3.mca"), new byte[12 * 1024]);
        Files.write(terrain.resolve("r.9.9.mca"), new byte[8192]);

        byte[] liveHeader = new byte[16 * 1024];
        liveHeader[3] = 2;
        Files.write(terrain.resolve("r.4.5.mca"), liveHeader);

        List<EmptyRegionInfo> result = V26_2ChunkStorageGateway.scanEmptyRegionFolders(directory);

        assertEquals(List.of(new EmptyRegionInfo(2, -3, 12 * 1024L)), result);
    }
}

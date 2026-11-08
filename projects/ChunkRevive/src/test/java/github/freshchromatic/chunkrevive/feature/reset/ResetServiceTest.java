package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.nms.ChunkArea;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationService;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import net.kyori.adventure.audience.Audience;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResetServiceTest {

    @Test
    void confirmationPoliciesUseCurrentResetConfiguration() {
        var config = new PluginConfig();
        config.safety.confirmTimeoutSeconds = 37;
        config.safety.bulkRegenConfirmThresholdChunks = 12;
        var service = new ResetService(mock(MarkRegistry.class), null, null, null, () -> config, Runnable::run);
        var plan = new ResetStrategyPlanner.Plan(List.of(), List.of(), List.of(new MarkedChunk("world", 0, 0,
            UUID.randomUUID(), 0)));

        assertEquals(37, service.confirmationTimeoutSeconds());
        assertFalse(service.bulkRegenerationRequiresConfirmation(12));
        assertTrue(service.bulkRegenerationRequiresConfirmation(13));
        assertFalse(service.bulkResetRequiresConfirmation(plan, 12));
        assertTrue(service.bulkResetRequiresConfirmation(plan, 13));
    }

    @Test
    void markedChunksInStructureReturnsOnlyMatchingTargets() {
        var registry = mock(MarkRegistry.class);
        UUID groupId = UUID.randomUUID();
        var structureChunk = new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0, groupId);
        var independentChunk = new MarkedChunk("world", 3, 4, UUID.randomUUID(), 0);
        when(registry.getMarkedChunks()).thenReturn(List.of(structureChunk, independentChunk));
        var service = new ResetService(registry, null, null, null, null, Runnable::run);

        assertEquals(List.of(structureChunk), service.markedChunksInStructure(groupId));
    }

    @Test
    void regenerationUsesMarkedStructureBounds() {
        var registry = mock(MarkRegistry.class);
        var structures = mock(StructureRegistry.class);
        var regeneration = mock(RegenerationService.class);
        var groupId = UUID.randomUUID();
        var target = new MarkedChunk("world", 3, -1, UUID.randomUUID(), 0, groupId);
        var group = new StructureGroup(groupId, "world", "village", 1, 5, -3, 2, 0, 0, 0, false);
        when(registry.getMarkedChunks()).thenReturn(List.of(target));
        when(registry.getRegenerationService()).thenReturn(regeneration);
        when(structures.getGroup(groupId)).thenReturn(Optional.of(group));
        when(regeneration.regenChunk(any(Audience.class), any(MarkedChunk.class), any(ChunkArea.class))).thenReturn(
            CompletableFuture.completedFuture(null));
        when(registry.onChunkRegenComplete(eq("world"), eq(3), eq(-1), any())).thenReturn(
            CompletableFuture.completedFuture(null));
        var service = new ResetService(registry, structures, null, null, null, Runnable::run);
        var sender = Audience.empty();

        assertEquals(ResetService.QueueStatus.QUEUED, service.regenerateSingle(sender, target));
        verify(regeneration).regenChunk(eq(sender), eq(target), eq(new ChunkArea(1, 5, -3, 2)));
        verify(registry).onChunkRegenComplete(eq("world"), eq(3), eq(-1), any());
    }

    @Test
    void regenerationWithoutStructureUsesNoAdditionalBounds() {
        var registry = mock(MarkRegistry.class);
        var structures = mock(StructureRegistry.class);
        var regeneration = mock(RegenerationService.class);
        var target = new MarkedChunk("world", 3, -1, UUID.randomUUID(), 0);
        when(registry.getMarkedChunks()).thenReturn(List.of(target));
        when(registry.getRegenerationService()).thenReturn(regeneration);
        when(regeneration.regenChunk(any(Audience.class), any(MarkedChunk.class), isNull())).thenReturn(
            CompletableFuture.completedFuture(null));
        when(registry.onChunkRegenComplete(eq("world"), eq(3), eq(-1), any())).thenReturn(
            CompletableFuture.completedFuture(null));
        var service = new ResetService(registry, structures, null, null, null, Runnable::run);
        var sender = Audience.empty();

        assertEquals(ResetService.QueueStatus.QUEUED, service.regenerateSingle(sender, target));
        verify(regeneration).regenChunk(sender, target, (ChunkArea) null);
        verify(registry).onChunkRegenComplete(eq("world"), eq(3), eq(-1), any());
    }

    @Test
    void failedRegenerationDoesNotMarkChunkComplete() {
        var registry = mock(MarkRegistry.class);
        var regeneration = mock(RegenerationService.class);
        var target = new MarkedChunk("world", 3, -1, UUID.randomUUID(), 0);
        when(registry.getRegenerationService()).thenReturn(regeneration);
        when(regeneration.regenChunk(any(Audience.class), any(MarkedChunk.class), isNull())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("generation failed")));
        var service = new ResetService(registry, null, null, null, null, Runnable::run);

        assertEquals(ResetService.QueueStatus.QUEUED, service.regenerateSingle(Audience.empty(), target));
        verify(registry, never()).onChunkRegenComplete(eq("world"), eq(3), eq(-1), any());
    }

    @Test
    void regenerationTargetsRespectScopeWorldAndBlockedStructures() {
        var marks = mock(MarkRegistry.class);
        var structures = mock(StructureRegistry.class);
        var config = new PluginConfig();
        config.worlds.list.add("denied");
        var policy = new WorldAccessPolicy(config);
        UUID player = UUID.randomUUID();
        UUID allowedId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        var independent = new MarkedChunk("world", 0, 0, player, 0);
        var allowed = new MarkedChunk("world", 1, 0, player, 0, allowedId);
        var blocked = new MarkedChunk("world", 2, 0, player, 0, blockedId);
        var denied = new MarkedChunk("denied", 3, 0, player, 0);
        when(marks.getMarkedChunks()).thenReturn(List.of(independent, allowed, blocked, denied));
        when(structures.getGroup(allowedId)).thenReturn(Optional.of(new StructureGroup(allowedId, "world", "village", 1,
            1, 0, 0, 0, 0, 0, false)));
        when(structures.getGroup(blockedId)).thenReturn(Optional.of(new StructureGroup(blockedId, "world", "village", 2,
            2, 0, 0, 0, 0, 0, true)));
        var service = new ResetService(marks, structures, policy, mock(DeletionService.class), () -> config,
            Runnable::run);

        assertEquals(List.of(independent), service.regenerationTargets(ResetService.RegenScope.CHUNKS));
        assertEquals(List.of(allowed), service.regenerationTargets(ResetService.RegenScope.STRUCTURES));
        assertEquals(List.of(independent, allowed), service.regenerationTargets(ResetService.RegenScope.ALL));
    }

    @Test
    void completeRegionsExcludePartialAndDisallowedWorlds() {
        var marks = mock(MarkRegistry.class);
        var config = new PluginConfig();
        config.worlds.list.add("denied");
        UUID player = UUID.randomUUID();
        var chunks = new ArrayList<MarkedChunk>();
        for (int x = -32; x < 0; x++) {
            for (int z = 0; z < 32; z++) {
                chunks.add(new MarkedChunk("world", x, z, player, 0));
                chunks.add(new MarkedChunk("denied", x, z, player, 0));
            }
        }
        chunks.add(new MarkedChunk("world", 0, 0, player, 0));
        when(marks.getMarkedChunks()).thenReturn(chunks);
        var service = new ResetService(marks, mock(StructureRegistry.class), new WorldAccessPolicy(config), mock(
            DeletionService.class), () -> config, Runnable::run);

        assertEquals(Map.of("world", Set.of(new AnvilRegionPos(-1, 0))), service.completeMarkedRegions());
    }
}

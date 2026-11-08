package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import github.freshchromatic.chunkrevive.feature.regeneration.RegenerationQueue;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructureRefreshSchedulerTest {

    @Test
    @SuppressWarnings("unchecked")
    void pendingProtectionCheckFromPreviousScheduleCannotStartAfterRestart() {
        var plugin = mock(Plugin.class);
        var config = new PluginConfig();
        config.structure.enabled = true;
        config.structure.refresh.enabled = true;
        config.structure.refresh.listMode = PluginConfig.Structure.ListMode.WHITELIST.name();
        config.structure.refresh.trackedStructures.put("minecraft:village", 1);
        var group = new StructureGroup(UUID.randomUUID(), "world", "minecraft:village", 0, 0, 0, 0, 1, 1, 0, false);
        var structures = mock(StructureRegistry.class);
        when(structures.getAllGroups()).thenReturn(List.of(group));
        when(structures.resolveEffectiveChunks("world", 0, 0, 0, 0)).thenReturn(List.of(new ChunkCoordinate(0, 0)));
        var queue = mock(RegenerationQueue.class);
        when(queue.isRunning()).thenReturn(false);
        var marks = mock(MarkRegistry.class);
        when(marks.getRegenerationQueue()).thenReturn(queue);
        var firstCheck = new CompletableFuture<ProtectedMarkExecutor.BatchResult>();
        var secondCheck = new CompletableFuture<ProtectedMarkExecutor.BatchResult>();
        var protectedMarks = mock(ProtectedMarkExecutor.class);
        when(protectedMarks.markBatch(anyList())).thenReturn(firstCheck, secondCheck);
        var scheduler = new StructureRefreshScheduler(plugin, config, new Messages(), structures, marks,
            new WorldAccessPolicy(config), protectedMarks);
        var timerTask = mock(github.freshchromatic.freshlib.scheduler.ScheduledTask.class);
        var timerCallbacks = new ArrayList<Runnable>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> taskScheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
            taskScheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(),
                anyLong())).thenAnswer(invocation -> {
                    timerCallbacks.add(invocation.getArgument(1));
                    return timerTask;
                });

            scheduler.start();
            timerCallbacks.getFirst().run();
            verify(protectedMarks).markBatch(anyList());

            scheduler.start();
            firstCheck.complete(new ProtectedMarkExecutor.BatchResult(List.of(refreshChunk(group)), List.of()));
            verify(queue, never()).start(any(), any(), any());

            timerCallbacks.getLast().run();
            verify(protectedMarks, org.mockito.Mockito.times(2)).markBatch(anyList());
            secondCheck.complete(new ProtectedMarkExecutor.BatchResult(List.of(), List.of()));
            verify(queue, never()).start(any(), any(), any());
        } finally {
            scheduler.stop();
        }
    }

    private static MarkedChunk refreshChunk(StructureGroup group) {
        return new MarkedChunk(group.world(), 0, 0, group.groupId(), System.currentTimeMillis(), group.groupId());
    }
}

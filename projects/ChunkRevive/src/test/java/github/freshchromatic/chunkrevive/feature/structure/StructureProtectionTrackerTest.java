package github.freshchromatic.chunkrevive.feature.structure;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureProtectionTrackerTest {

    @Test
    void schedulesPlayerReadsOnEntitySchedulersAndAppliesOneDeduplicatedGlobalUpdate() {
        var plugin = mock(Plugin.class);
        var firstPlayer = player("world", 32, 48);
        var secondPlayer = player("world", 64, 80);
        var config = new PluginConfig();
        config.structure.enabled = true;
        config.structure.refresh.listMode = PluginConfig.Structure.ListMode.WHITELIST.name();
        config.structure.refresh.trackedStructures.put("minecraft:village", 1);
        UUID groupId = UUID.randomUUID();
        var group = new StructureGroup(groupId, "world", "minecraft:village", 0, 1, 0, 1, 1, 2, 0, false);
        var marks = mock(MarkRegistry.class);
        when(marks.getMarkedChunksNear(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(new MarkedChunk(
            "world", 1, 2, UUID.randomUUID(), 1, groupId)));
        var structures = mock(StructureRegistry.class);
        when(structures.getAllGroups()).thenReturn(List.of(group));
        when(structures.getGroup(groupId)).thenReturn(Optional.of(group));
        var tracker = new StructureProtectionTracker(plugin, config, new Messages(), marks, structures);
        var timerTask = mock(ScheduledTask.class);
        var timerCallback = new AtomicReference<Runnable>();
        var firstPlayerRead = new AtomicReference<Runnable>();
        var secondPlayerRead = new AtomicReference<Runnable>();
        var globalUpdate = new AtomicReference<Runnable>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(firstPlayer, secondPlayer));
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(
                invocation -> {
                    timerCallback.set(invocation.getArgument(1));
                    return timerTask;
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                firstPlayer))).thenAnswer(invocation -> {
                    firstPlayerRead.set(invocation.getArgument(1));
                    return null;
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                secondPlayer))).thenAnswer(invocation -> {
                    secondPlayerRead.set(invocation.getArgument(1));
                    return null;
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                globalUpdate.set(invocation.getArgument(1));
                return null;
            });

            tracker.start();
            timerCallback.get().run();

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                firstPlayer)));
            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                secondPlayer)));
            firstPlayerRead.get().run();
            assertNull(globalUpdate.get());
            secondPlayerRead.get().run();
            globalUpdate.get().run();

            verify(marks).getMarkedChunksNear("world", 2, 3, config.structure.protection.radiusChunks);
            verify(marks).getMarkedChunksNear("world", 4, 5, config.structure.protection.radiusChunks);
            verify(structures).addProtectionTicks(groupId, 20L);
        } finally {
            tracker.stop();
        }
    }

    @Test
    void ignoresAPlayerScanThatCompletesAfterTrackerStops() {
        var plugin = mock(Plugin.class);
        var player = player("world", 0, 0);
        var config = new PluginConfig();
        config.structure.enabled = true;
        var marks = mock(MarkRegistry.class);
        var structures = mock(StructureRegistry.class);
        var tracker = new StructureProtectionTracker(plugin, config, new Messages(), marks, structures);
        var timerCallback = new AtomicReference<Runnable>();
        var playerRead = new AtomicReference<Runnable>();
        var globalUpdate = new AtomicReference<Runnable>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(
                invocation -> {
                    timerCallback.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                player))).thenAnswer(invocation -> {
                    playerRead.set(invocation.getArgument(1));
                    return null;
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                globalUpdate.set(invocation.getArgument(1));
                return null;
            });

            tracker.start();
            timerCallback.get().run();
            tracker.stop();
            playerRead.get().run();
            globalUpdate.get().run();

            verify(structures, never()).getAllGroups();
            verify(structures, never()).addProtectionTicks(any(), anyLong());
            verify(structures, never()).resetProtectionTicks(any());
        } finally {
            tracker.stop();
        }
    }

    @Test
    void fatalPlayerReadCompletesScanAndPropagatesError() {
        var plugin = mock(Plugin.class);
        var player = player("world", 0, 0);
        var config = new PluginConfig();
        config.structure.enabled = true;
        var marks = mock(MarkRegistry.class);
        var fatal = new AssertionError("fatal player read");
        when(marks.getMarkedChunksNear(anyString(), anyInt(), anyInt(), anyInt())).thenThrow(fatal);
        var structures = mock(StructureRegistry.class);
        var tracker = new StructureProtectionTracker(plugin, config, new Messages(), marks, structures);
        var timerCallback = new AtomicReference<Runnable>();
        var playerRead = new AtomicReference<Runnable>();
        var globalUpdate = new AtomicReference<Runnable>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            scheduler.when(() -> Scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(
                invocation -> {
                    timerCallback.set(invocation.getArgument(1));
                    return mock(ScheduledTask.class);
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                player))).thenAnswer(invocation -> {
                    playerRead.set(invocation.getArgument(1));
                    return null;
                });
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                globalUpdate.set(invocation.getArgument(1));
                return null;
            });

            tracker.start();
            timerCallback.get().run();

            assertThrows(AssertionError.class, () -> playerRead.get().run());
            globalUpdate.get().run();
            timerCallback.get().run();

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                player)), times(2));
        } finally {
            tracker.stop();
        }
    }

    private static Player player(String worldName, int blockX, int blockZ) {
        var player = mock(Player.class);
        var world = mock(World.class);
        var location = mock(Location.class);
        when(world.getName()).thenReturn(worldName);
        when(location.getBlockX()).thenReturn(blockX);
        when(location.getBlockZ()).thenReturn(blockZ);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location);
        return player;
    }
}

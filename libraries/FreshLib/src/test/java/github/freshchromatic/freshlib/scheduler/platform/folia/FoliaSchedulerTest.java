package github.freshchromatic.freshlib.scheduler.platform.folia;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FoliaSchedulerTest {

    @Test
    void entityTaskIsCancelledWhenEntitySchedulerIsAlreadyRetired() {
        var plugin = mock(Plugin.class);
        var entity = mock(Entity.class);
        var entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(entityScheduler.run(eq(plugin), org.mockito.ArgumentMatchers.<Consumer<ScheduledTask>>any(),
            isNull())).thenReturn(null);

        var result = new FoliaScheduler().runTask(plugin, () -> {
        }, entity);

        assertTrue(result.isCancelled());
        assertSame(plugin, result.getOwner());
        assertNull(result.getHandle());
        result.cancel();
    }

    @Test
    void entityCallSyncMethodCompletesAsCancelledWhenEntitySchedulerIsAlreadyRetired() {
        var plugin = mock(Plugin.class);
        var entity = mock(Entity.class);
        var entityScheduler = mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(entityScheduler.run(eq(plugin), org.mockito.ArgumentMatchers.<Consumer<ScheduledTask>>any(), any(
            Runnable.class))).thenReturn(null);

        Future<String> result = new FoliaScheduler().callSyncMethod(plugin, () -> "result", entity);

        assertTrue(result.isCancelled());
        assertThrows(CancellationException.class, result::get);
    }

    @Test
    void callSyncMethodCompletesFutureAndRethrowsFatalErrors() throws Exception {
        var plugin = mock(Plugin.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        var scheduledTask = mock(ScheduledTask.class);
        var scheduledCallback = new AtomicReference<Consumer<ScheduledTask>>();
        var fatalError = new AssertionError("fatal callback failure");

        when(globalScheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            scheduledCallback.set(invocation.getArgument(1));
            return scheduledTask;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            Future<String> result = new FoliaScheduler().callSyncMethod(plugin, () -> {
                throw fatalError;
            });

            AssertionError thrown = assertThrows(AssertionError.class, () -> scheduledCallback.get().accept(
                scheduledTask));
            ExecutionException completion = assertThrows(ExecutionException.class, result::get);

            assertSame(fatalError, thrown);
            assertSame(fatalError, completion.getCause());
        }
    }

    @Test
    void executeOrScheduleSyncRunsDirectlyWhenPluginIsDisabled() {
        var plugin = mock(Plugin.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        var caller = Thread.currentThread();
        var executedThread = new AtomicReference<Thread>();
        when(plugin.isEnabled()).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            new FoliaScheduler().executeOrScheduleSync(plugin, () -> executedThread.set(Thread.currentThread()));

            assertSame(caller, executedThread.get());
            verify(globalScheduler, never()).run(any(), any());
        }
    }

    @Test
    void executeOrScheduleSyncRunsDirectlyWhenSchedulerRejectsTask() {
        var plugin = mock(Plugin.class);
        var globalScheduler = mock(GlobalRegionScheduler.class);
        var caller = Thread.currentThread();
        var executedThread = new AtomicReference<Thread>();
        when(plugin.isEnabled()).thenReturn(true);
        doThrow(new IllegalPluginAccessException("plugin disabled")).when(globalScheduler).run(eq(plugin), any());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            new FoliaScheduler().executeOrScheduleSync(plugin, () -> executedThread.set(Thread.currentThread()));

            assertSame(caller, executedThread.get());
        }
    }

    @Test
    void executeOrScheduleSyncRunsDirectlyWhenRegionSchedulerRejectsTask() {
        var plugin = mock(Plugin.class);
        var regionScheduler = mock(RegionScheduler.class);
        var world = mock(World.class);
        var location = mock(Location.class);
        var chunk = mock(Chunk.class);
        var rejection = new IllegalPluginAccessException("plugin disabled");
        var caller = Thread.currentThread();
        var executedThread = new AtomicReference<Thread>();
        when(plugin.isEnabled()).thenReturn(true);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(4);
        when(chunk.getZ()).thenReturn(7);
        doThrow(rejection).when(regionScheduler).execute(eq(plugin), eq(location), any(Runnable.class));
        doThrow(rejection)
            .when(regionScheduler)
            .execute(eq(plugin), eq(world), anyInt(), anyInt(), any(Runnable.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(location)).thenReturn(false);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 4, 7)).thenReturn(false);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

            var scheduler = new FoliaScheduler();
            Runnable task = () -> executedThread.set(Thread.currentThread());
            scheduler.executeOrScheduleSync(plugin, task, location);
            assertSame(caller, executedThread.get());

            executedThread.set(null);
            scheduler.executeOrScheduleSync(plugin, task, world, 4, 7);
            assertSame(caller, executedThread.get());

            executedThread.set(null);
            scheduler.executeOrScheduleSync(plugin, task, chunk);
            assertSame(caller, executedThread.get());
        }
    }

    @Test
    void executeOrScheduleSyncRunsDirectlyWhenEntitySchedulerIsRetired() {
        var plugin = mock(Plugin.class);
        var entity = mock(Entity.class);
        var entityScheduler = mock(EntityScheduler.class);
        var caller = Thread.currentThread();
        var executedThread = new AtomicReference<Thread>();
        when(plugin.isEnabled()).thenReturn(true);
        when(entity.getScheduler()).thenReturn(entityScheduler);
        when(entityScheduler.run(eq(plugin), org.mockito.ArgumentMatchers.<Consumer<ScheduledTask>>any(),
            isNull())).thenReturn(null);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(false);

            new FoliaScheduler().executeOrScheduleSync(plugin, () -> executedThread.set(Thread.currentThread()),
                entity);

            assertSame(caller, executedThread.get());
        }
    }
}

package github.freshchromatic.chunkrevive.feature.reset;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.nms.ChunkArea;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class DeletionWorldAccess {

    private final ChunkRevivePlugin plugin;
    private final MarkRegistry      markRegistry;

    DeletionWorldAccess(ChunkRevivePlugin plugin, MarkRegistry markRegistry) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
    }

    World world(String name) {
        return callGlobal(() -> Bukkit.getWorld(name));
    }

    boolean regionHasResidence(World world, AnvilRegionPos region) {
        return callGlobal(() -> {
            if (!markRegistry.getLandProtection().isEnabled()) {
                return false;
            }
            for (int cx = region.minChunkX(); cx <= region.maxChunkX(); cx++) {
                for (int cz = region.minChunkZ(); cz <= region.maxChunkZ(); cz++) {
                    if (markRegistry.getLandProtection().hasClaim(world, cx, cz)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    boolean hasNearbyPlayer(World world, ChunkArea area) {
        PlayerSnapshotRequest request = callGlobal(() -> new PlayerSnapshotRequest(world.getUID(), List.copyOf(
            Bukkit.getOnlinePlayers())));
        List<Future<PlayerChunkSnapshot>> snapshots = new ArrayList<>();
        for (Player player : request.players()) {
            snapshots.add(Scheduler.callSyncMethod(plugin, () -> {
                if (!player.isValid()) {
                    return null;
                }
                Location location = player.getLocation();
                return new PlayerChunkSnapshot(location.getWorld().getUID(), location.getBlockX() >> 4,
                    location.getBlockZ() >> 4);
            }, () -> null, player));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        for (Future<PlayerChunkSnapshot> future : snapshots) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return true;
                }
                PlayerChunkSnapshot snapshot = future.get(remaining, TimeUnit.NANOSECONDS);
                if (snapshot != null && snapshot.world().equals(request.world()) && area.contains(snapshot.chunkX(),
                    snapshot.chunkZ())) {
                    return true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            } catch (CancellationException | ExecutionException | TimeoutException failure) {
                DeletionFailurePolicy.rethrowFatalFailure(failure);
                // Without a reliable player snapshot, deletion must remain paused to protect active regions.
                return true;
            }
        }
        return false;
    }

    boolean hasClaim(World world, int chunkX, int chunkZ) {
        return callGlobal(() -> markRegistry.getLandProtection().hasClaim(world, chunkX, chunkZ));
    }

    private <T> T callGlobal(Callable<T> action) {
        if (Scheduler.isGlobalTickThread()) {
            try {
                return action.call();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Global region task failed", failure);
            }
        }

        var result = new CompletableFuture<T>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a global region task", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            DeletionFailurePolicy.rethrowFatalFailure(failure);
            throw new IllegalStateException("Global region task did not complete", failure);
        }
    }

    private record PlayerSnapshotRequest(UUID world, List<Player> players) {}

    private record PlayerChunkSnapshot(UUID world, int chunkX, int chunkZ) {}
}

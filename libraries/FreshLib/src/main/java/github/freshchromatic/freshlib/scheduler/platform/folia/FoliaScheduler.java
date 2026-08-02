/*
 * This file is part of PlatformScheduler-Folia.
 *
 * Copyright (C) 2020 - 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2020 - 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package github.freshchromatic.freshlib.scheduler.platform.folia;

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FoliaScheduler implements PlatformScheduler {

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isGlobalTickThread() {
        return Bukkit.isGlobalTickThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    @Override
    public boolean isOwnedByCurrentRegion(Chunk chunk) {
        return isOwnedByCurrentRegion(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location, int squareRadiusChunks) {
        return Bukkit.isOwnedByCurrentRegion(location, squareRadiusChunks);
    }

    @Override
    public boolean isOwnedByCurrentRegion(Chunk chunk, int squareRadiusChunks) {
        return isOwnedByCurrentRegion(chunk.getWorld(), chunk.getX(), chunk.getZ(), squareRadiusChunks);
    }

    @Override
    public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ, int squareRadiusChunks) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ, squareRadiusChunks);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task) {
        // isPrimaryThread() returns true for ANY Folia tick thread (region or global).
        // isGlobalTickThread() is too narrow — it misses region threads.
        if (isPrimaryThread() || !plugin.isEnabled()) {
            task.run();
        } else {
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, st -> task.run());
            } catch (IllegalPluginAccessException e) {
                task.run();
            }
        }
    }


    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Entity entity) {
        executeOrScheduleSync(plugin, task, null, entity);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        if (isOwnedByCurrentRegion(entity) || !plugin.isEnabled()) {
            task.run();
        } else {
            try {
                entity.getScheduler().run(plugin, st -> task.run(), retired);
            } catch (IllegalPluginAccessException e) {
                task.run();
            }
        }
    }


    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Location location) {
        if (isOwnedByCurrentRegion(location) || !plugin.isEnabled()) {
            task.run();
        } else {
            try {
                Bukkit.getRegionScheduler().execute(plugin, location, task);
            } catch (IllegalPluginAccessException e) {
                task.run();
            }
        }
    }


    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Chunk chunk) {
        executeOrScheduleSync(plugin, task, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        if (isOwnedByCurrentRegion(world, chunkX, chunkZ) || !plugin.isEnabled()) {
            task.run();
        } else {
            try {
                Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
            } catch (IllegalPluginAccessException e) {
                task.run();
            }
        }
    }


    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task, Entity entity) {
        return runTask(plugin, task, null, entity);
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Entity entity) {
        return runTaskLater(plugin, task, null, delay, entity);
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Entity entity) {
        return runTaskTimer(plugin, task, null, delay, period, entity);
    }

    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        return new FoliaScheduledTask(entity.getScheduler().run(plugin, st -> task.run(), retired));
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, Runnable retired, long delay, Entity entity) {
        return delay <= 0 ? runTask(plugin, task, retired, entity) : new FoliaScheduledTask(entity.getScheduler().runDelayed(plugin, st -> task.run(), retired, delay));
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, Runnable retired, long delay, long period, Entity entity) {
        return new FoliaScheduledTask(entity.getScheduler().runAtFixedRate(plugin, st -> task.run(), retired, Math.max(1, delay), period));
    }

    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task, Location location) {
        return new FoliaScheduledTask(Bukkit.getRegionScheduler().run(plugin, location, st -> task.run()));
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Location location) {
        return delay <= 0 ? runTask(plugin, task, location) : new FoliaScheduledTask(Bukkit.getRegionScheduler().runDelayed(plugin, location, st -> task.run(), delay));
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Location location) {
        return new FoliaScheduledTask(Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, st -> task.run(), Math.max(1, delay), period));
    }

    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task, Chunk chunk) {
        return runTask(plugin, task, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Chunk chunk) {
        return runTaskLater(plugin, task, delay, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Chunk chunk) {
        return runTaskTimer(plugin, task, delay, period, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        return new FoliaScheduledTask(Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, st -> task.run()));
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, World world, int chunkX, int chunkZ) {
        return delay <= 0 ? runTask(plugin, task, world, chunkX, chunkZ) : new FoliaScheduledTask(Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, st -> task.run(), delay));
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, World world, int chunkX, int chunkZ) {
        return new FoliaScheduledTask(Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, chunkX, chunkZ, st -> task.run(), Math.max(1, delay), period));
    }

    @Override
    public FoliaScheduledTask runTask(Plugin plugin, Runnable task) {
        return new FoliaScheduledTask(Bukkit.getGlobalRegionScheduler().run(plugin, st -> task.run()));
    }

    @Override
    public FoliaScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        return delay <= 0 ? runTask(plugin, task) : new FoliaScheduledTask(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, st -> task.run(), delay));
    }

    @Override
    public FoliaScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return new FoliaScheduledTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, st -> task.run(), Math.max(1, delay), period));
    }

    @Override
    public FoliaScheduledTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return new FoliaScheduledTask(Bukkit.getAsyncScheduler().runNow(plugin, st -> task.run()));
    }

    @Override
    public FoliaScheduledTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return delay <= 0 ? runTaskAsynchronously(plugin, task) : new FoliaScheduledTask(Bukkit.getAsyncScheduler().runDelayed(plugin, st -> task.run(), delay * 50, TimeUnit.MILLISECONDS));
    }

    @Override
    public FoliaScheduledTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return new FoliaScheduledTask(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, st -> task.run(), Math.max(1, delay * 50), period * 50, TimeUnit.MILLISECONDS));
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Entity entity) {
        return callSyncMethod(plugin, task, task, entity);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Callable<T> retired, Entity entity) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = toFuture(future, task);
        Runnable runnableRetired = toFuture(future, retired);
        entity.getScheduler().run(plugin, st -> runnable.run(), runnableRetired);
        return future;
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Location location) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = toFuture(future, task);
        Bukkit.getRegionScheduler().run(plugin, location, st -> runnable.run());
        return future;
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Chunk chunk) {
        return callSyncMethod(plugin, task, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, World world, int chunkX, int chunkZ) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = toFuture(future, task);
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, st -> runnable.run());
        return future;
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = toFuture(future, task);
        Bukkit.getGlobalRegionScheduler().run(plugin, st -> runnable.run());
        return future;
    }

    private static <T> Runnable toFuture(CompletableFuture<T> future, Callable<T> task) {
        return () -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
    }

    @Override
    public boolean teleport(Entity entity, Location location) {
        entity.teleportAsync(location);
        return true;
    }

}

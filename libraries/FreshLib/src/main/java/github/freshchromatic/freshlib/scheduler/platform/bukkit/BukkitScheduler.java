/*
 * This file is part of PlatformScheduler-Bukkit.
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

package github.freshchromatic.freshlib.scheduler.platform.bukkit;

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class BukkitScheduler implements PlatformScheduler {

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isGlobalTickThread() {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Chunk chunk) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(Chunk chunk, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task) {
        if (isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Entity entity) {
        executeOrScheduleSync(plugin, task);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        executeOrScheduleSync(plugin, task);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Location location) {
        executeOrScheduleSync(plugin, task);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, Chunk chunk) {
        executeOrScheduleSync(plugin, task);
    }

    @Override
    public void executeOrScheduleSync(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        executeOrScheduleSync(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task, Entity entity) {
        return runTask(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Entity entity) {
        return runTaskLater(plugin, task, delay);
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Entity entity) {
        return runTaskTimer(plugin, task, delay, period);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        return runTask(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, Runnable retired, long delay, Entity entity) {
        return runTaskLater(plugin, task, delay);
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, Runnable retired, long delay, long period, Entity entity) {
        return runTaskTimer(plugin, task, delay, period);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task, Location location) {
        return runTask(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Location location) {
        return runTaskLater(plugin, task, delay);
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Location location) {
        return runTaskTimer(plugin, task, delay, period);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task, Chunk chunk) {
        return runTask(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Chunk chunk) {
        return runTaskLater(plugin, task, delay);
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Chunk chunk) {
        return runTaskTimer(plugin, task, delay, period);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        return runTask(plugin, task);
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, World world, int chunkX, int chunkZ) {
        return runTaskLater(plugin, task, delay);
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, World world, int chunkX, int chunkZ) {
        return runTaskTimer(plugin, task, delay, period);
    }

    @Override
    public BukkitScheduledTask runTask(Plugin plugin, Runnable task) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTask(plugin, task));
    }

    @Override
    public BukkitScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
    }

    @Override
    public BukkitScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
    }

    @Override
    public BukkitScheduledTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public BukkitScheduledTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay));
    }

    @Override
    public BukkitScheduledTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return new BukkitScheduledTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period));
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Entity entity) {
        return callSyncMethod(plugin, task);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Callable<T> retired, Entity entity) {
        return callSyncMethod(plugin, task);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Location location) {
        return callSyncMethod(plugin, task);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Chunk chunk) {
        return callSyncMethod(plugin, task);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, World world, int chunkX, int chunkZ) {
        return callSyncMethod(plugin, task);
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        return Bukkit.getScheduler().callSyncMethod(plugin, task);
    }

    @Override
    public boolean teleport(Entity entity, Location location) {
        return entity.teleport(location);
    }

}

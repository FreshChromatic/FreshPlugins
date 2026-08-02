/*
 * This file is part of PlatformScheduler.
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

package github.freshchromatic.freshlib.scheduler;

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduler;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class Scheduler {

    private static final PlatformScheduler platformScheduler = initializePlatformScheduler();

    private static PlatformScheduler initializePlatformScheduler() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return (PlatformScheduler) Class.forName("github.freshchromatic.freshlib.scheduler.platform.folia.FoliaScheduler").getConstructor().newInstance();
        } catch (Throwable ignored) {}
        try {
            return (PlatformScheduler) Class.forName("github.freshchromatic.freshlib.scheduler.platform.bukkit.BukkitScheduler").getConstructor().newInstance();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        throw new IllegalArgumentException("Unable to find suitable implementation of PlatformScheduler");
    }

    public static PlatformScheduler getPlatform() {
        return platformScheduler;
    }

    public static boolean isPrimaryThread() {
        return platformScheduler.isPrimaryThread();
    }

    public static boolean isGlobalTickThread() {
        return platformScheduler.isGlobalTickThread();
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        return platformScheduler.isOwnedByCurrentRegion(entity);
    }

    public static boolean isOwnedByCurrentRegion(Location location) {
        return platformScheduler.isOwnedByCurrentRegion(location);
    }

    public static boolean isOwnedByCurrentRegion(Chunk chunk) {
        return platformScheduler.isOwnedByCurrentRegion(chunk);
    }

    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return platformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isOwnedByCurrentRegion(Location location, int squareRadiusChunks) {
        return platformScheduler.isOwnedByCurrentRegion(location, squareRadiusChunks);
    }

    public static boolean isOwnedByCurrentRegion(Chunk chunk, int squareRadiusChunks) {
        return platformScheduler.isOwnedByCurrentRegion(chunk, squareRadiusChunks);
    }

    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ, int squareRadiusChunks) {
        return platformScheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ, squareRadiusChunks);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task) {
        platformScheduler.executeOrScheduleSync(plugin, task);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task, Entity entity) {
        platformScheduler.executeOrScheduleSync(plugin, task, entity);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        platformScheduler.executeOrScheduleSync(plugin, task, retired, entity);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task, Location location) {
        platformScheduler.executeOrScheduleSync(plugin, task, location);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task, Chunk chunk) {
        platformScheduler.executeOrScheduleSync(plugin, task, chunk);
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        platformScheduler.executeOrScheduleSync(plugin, task, world, chunkX, chunkZ);
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task, Entity entity) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task, entity));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Entity entity) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, delay, entity));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Entity entity) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, delay, period, entity));
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task, Runnable retired, Entity entity) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task, retired, entity));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, Runnable retired, long delay, Entity entity) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, retired, delay, entity));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, Runnable retired, long delay, long period, Entity entity) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, retired, delay, period, entity));
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task, Location location) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task, location));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Location location) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, delay, location));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Location location) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, delay, period, location));
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task, Chunk chunk) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task, chunk));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, Chunk chunk) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, delay, chunk));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, Chunk chunk) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, delay, period, chunk));
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task, World world, int chunkX, int chunkZ) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task, world, chunkX, chunkZ));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay, World world, int chunkX, int chunkZ) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, delay, world, chunkX, chunkZ));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period, World world, int chunkX, int chunkZ) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, delay, period, world, chunkX, chunkZ));
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable task) {
        return new ScheduledTask(platformScheduler.runTask(plugin, task));
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        return new ScheduledTask(platformScheduler.runTaskLater(plugin, task, delay));
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return new ScheduledTask(platformScheduler.runTaskTimer(plugin, task, delay, period));
    }
    
    public static ScheduledTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return new ScheduledTask(platformScheduler.runTaskAsynchronously(plugin, task));
    }

    public static ScheduledTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return new ScheduledTask(platformScheduler.runTaskLaterAsynchronously(plugin, task, delay));
    }
    
    public static ScheduledTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return new ScheduledTask(platformScheduler.runTaskTimerAsynchronously(plugin, task, delay, period));
    }
    
    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Entity entity) {
        return platformScheduler.callSyncMethod(plugin, task, entity);
    }

    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Callable<T> retired, Entity entity) {
        return platformScheduler.callSyncMethod(plugin, task, retired, entity);
    }
    
    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Location location) {
        return platformScheduler.callSyncMethod(plugin, task, location);
    }

    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, Chunk chunk) {
        return platformScheduler.callSyncMethod(plugin, task, chunk);
    }

    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task, World world, int chunkX, int chunkZ) {
        return platformScheduler.callSyncMethod(plugin, task, world, chunkX, chunkZ);
    }
    
    public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        return platformScheduler.callSyncMethod(plugin, task);
    }

    public static boolean teleport(Entity entity, Location location) {
        return platformScheduler.teleport(entity, location);
    }
    
}

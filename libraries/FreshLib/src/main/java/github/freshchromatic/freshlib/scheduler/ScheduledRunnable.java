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

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public abstract class ScheduledRunnable implements Runnable {
    
    private ScheduledTask task;
    
    public synchronized boolean isCancelled() {
        checkScheduled();
        return task.isCancelled();
    }
    
    public synchronized void cancel() {
        checkScheduled();
        task.cancel();
    }

    public synchronized ScheduledTask runTask(Plugin plugin, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, entity));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, entity));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, entity));
    }

    public synchronized ScheduledTask runTask(Plugin plugin, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, location));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, location));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, location));
    }

    public synchronized ScheduledTask runTask(Plugin plugin, Chunk chunk) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, chunk));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, Chunk chunk) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, chunk));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, Chunk chunk) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, chunk));
    }

    public synchronized ScheduledTask runTask(Plugin plugin, World world, int chunkX, int chunkZ) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, world, chunkX, chunkZ));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, World world, int chunkX, int chunkZ) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, world, chunkX, chunkZ));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, World world, int chunkX, int chunkZ) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, world, chunkX, chunkZ));
    }

    public synchronized ScheduledTask runTask(Plugin plugin) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this));
    }
    
    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay));
    }
    
    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period));
    }

    public synchronized ScheduledTask runTaskAsynchronously(Plugin plugin) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskAsynchronously(plugin, this));
    }

    public synchronized ScheduledTask runTaskLaterAsynchronously(Plugin plugin, long delay) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLaterAsynchronously(plugin, this, delay));
    }
    
    public synchronized ScheduledTask runTaskTimerAsynchronously(Plugin plugin, long delay, long period) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimerAsynchronously(plugin, this, delay, period));
    }

    private void checkScheduled() {
        if (task == null) {
            throw new IllegalStateException("Not scheduled yet");
        }
    }

    private void checkNotYetScheduled() {
        if (task != null) {
            throw new IllegalStateException("Already scheduled as " + task);
        }
    }

    private ScheduledTask setupTask(ScheduledTask task) {
        this.task = task;
        return task;
    }
}

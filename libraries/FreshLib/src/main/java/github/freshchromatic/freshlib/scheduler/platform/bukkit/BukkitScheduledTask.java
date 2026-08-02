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

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

public class BukkitScheduledTask implements PlatformScheduledTask<BukkitTask> {

    private final BukkitTask task;

    public BukkitScheduledTask(BukkitTask task) {
        this.task = task;
    }

    @Override
    public boolean isCancelled() {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        int taskId = task.getTaskId();
        return !scheduler.isCurrentlyRunning(taskId) && !scheduler.isQueued(taskId);
    }

    @Override
    public void cancel() {
        task.cancel();
    }

    @Override
    public Plugin getOwner() {
        return task.getOwner();
    }

    @Override
    public BukkitTask getHandle() {
        return task;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        BukkitScheduledTask that = (BukkitScheduledTask) object;
        return Objects.equals(task, that.task);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(task);
    }
}

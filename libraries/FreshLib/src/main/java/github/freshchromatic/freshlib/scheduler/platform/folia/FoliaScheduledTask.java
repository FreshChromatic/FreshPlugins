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

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduledTask;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public class FoliaScheduledTask implements PlatformScheduledTask<ScheduledTask> {

    private final ScheduledTask task;

    public FoliaScheduledTask(ScheduledTask task) {
        this.task = task;
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public void cancel() {
        task.cancel();
    }

    @Override
    public Plugin getOwner() {
        return task.getOwningPlugin();
    }

    @Override
    public ScheduledTask getHandle() {
        return task;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        FoliaScheduledTask that = (FoliaScheduledTask) object;
        return Objects.equals(task, that.task);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(task);
    }
}

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

import github.freshchromatic.freshlib.scheduler.platform.PlatformScheduledTask;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public class ScheduledTask {

    private final PlatformScheduledTask<?> platformScheduledTask;

    public ScheduledTask(PlatformScheduledTask<?> platformScheduledTask) {
        this.platformScheduledTask = platformScheduledTask;
    }

    public boolean isCancelled() {
        return platformScheduledTask.isCancelled();
    }

    public void cancel() {
        platformScheduledTask.cancel();
    }

    public Plugin getOwner() {
        return platformScheduledTask.getOwner();
    }

    public PlatformScheduledTask<?> getPlatform() {
        return platformScheduledTask;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        ScheduledTask that = (ScheduledTask) object;
        return Objects.equals(platformScheduledTask, that.platformScheduledTask);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(platformScheduledTask);
    }
}

package github.freshchromatic.freshlib.scheduler.platform.bukkit;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitScheduledTaskTest {

    @Test
    void reportsOnlyCancellationInsteadOfCompletion() {
        var task = mock(BukkitTask.class);
        var scheduledTask = new BukkitScheduledTask(task);

        when(task.isCancelled()).thenReturn(false);
        assertFalse(scheduledTask.isCancelled());

        when(task.isCancelled()).thenReturn(true);
        assertTrue(scheduledTask.isCancelled());
    }
}

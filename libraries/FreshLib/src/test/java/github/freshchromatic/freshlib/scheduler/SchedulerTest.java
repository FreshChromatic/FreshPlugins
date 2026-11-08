package github.freshchromatic.freshlib.scheduler;

import github.freshchromatic.freshlib.scheduler.platform.bukkit.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SchedulerTest {

    @Test
    void selectsBukkitSchedulerWithoutFoliaServer() {
        assertInstanceOf(BukkitScheduler.class, Scheduler.getPlatform());
    }
}

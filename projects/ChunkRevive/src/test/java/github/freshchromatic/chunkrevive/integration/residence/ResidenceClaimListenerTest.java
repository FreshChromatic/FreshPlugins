package github.freshchromatic.chunkrevive.integration.residence;

import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ResidenceClaimListenerTest {

    @Test
    void notifiesProtectionChangeBeforeStartingMarkRemoval() {
        var plugin = mock(Plugin.class);
        var markService = mock(MarkService.class);
        var event = mock(ResidenceCreationEvent.class);
        var area = mock(CuboidArea.class);
        var low = new Vector(16, 0, 32);
        var high = new Vector(47, 255, 63);
        when(event.getPhysicalArea()).thenReturn(area);
        when(area.getWorldName()).thenReturn("world");
        when(area.getLowVector()).thenReturn(low);
        when(area.getHighVector()).thenReturn(high);

        var order = new ArrayList<String>();
        doAnswer(invocation -> {
            order.add("unmark");
            return new CompletableFuture<Integer>();
        })
            .when(markService)
            .unmarkArea(eq("world"), eq(1), eq(2), eq(2), eq(3));
        var listener = new ResidenceClaimListener(plugin, markService, (world, minCx, maxCx, minCz, maxCz) -> order.add(
            "protection-change"));
        var scheduledTask = new AtomicReference<Runnable>();

        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(() -> Scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                scheduledTask.set(invocation.getArgument(1));
                return null;
            });

            listener.onResidenceCreated(event);
            assertEquals(List.of("protection-change"), order);
            scheduledTask.get().run();
        }

        assertEquals(List.of("protection-change", "unmark"), order);
    }
}

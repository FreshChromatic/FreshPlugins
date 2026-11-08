package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiHistoryTest {

    @Test
    void doesNotAddTheCurrentGuiTwice() {
        var history = new GuiHistory<String>((player, gui) -> {
        });
        var player = player();
        String gui = "gui";

        history.add(player, gui);
        history.add(player, gui);

        assertEquals(1, history.get(player).size());
        assertEquals(gui, history.get(player).peekLast());
    }

    @Test
    void returnsToThePreviousGuiAndRemovesCurrentEntry() {
        var opened = new AtomicReference<String>();
        var history = new GuiHistory<String>((player, gui) -> opened.set(gui));
        var player = player();
        String previous = "previous";
        String current = "current";
        history.add(player, previous);
        history.add(player, current);

        assertTrue(history.goBack(player));

        assertEquals(1, history.get(player).size());
        assertEquals(previous, history.get(player).peekLast());
        assertEquals(previous, opened.get());
    }

    @Test
    void returnsFalseWhenNoPreviousGuiExists() {
        var history = new GuiHistory<String>((player, gui) -> {
        });
        var player = player();
        history.add(player, "current");

        assertFalse(history.goBack(player));
    }

    @Test
    void clearReturnsAndRemovesThePlayersHistory() {
        var history = new GuiHistory<String>((player, gui) -> {
        });
        var player = player();
        history.add(player, "first");
        history.add(player, "current");

        var cleared = history.clear(player);

        assertEquals(2, cleared.size());
        assertTrue(history.get(player).isEmpty());
    }

    @Test
    void changesToReturnedHistoryDoNotChangeTheOwnedHistory() {
        var history = new GuiHistory<String>((player, gui) -> {
        });
        var player = player();
        history.add(player, "current");

        history.get(player).clear();

        assertEquals(1, history.get(player).size());
        assertEquals("current", history.get(player).peekLast());
    }

    @Test
    void concurrentAddsForOnePlayerAreSerialized() throws Exception {
        var history = new GuiHistory<Integer>((player, gui) -> {
        });
        var player = player();
        int callers = 8;
        int entriesPerCaller = 100;
        var ready = new CountDownLatch(callers);
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        var tasks = new ArrayList<Future<?>>(callers);

        try {
            for (int caller = 0; caller < callers; caller++) {
                int callerId = caller;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent history test did not start");
                    }
                    for (int entry = 0; entry < entriesPerCaller; entry++) {
                        history.add(player, callerId * entriesPerCaller + entry);
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(callers * entriesPerCaller, history.get(player).size());
    }

    @Test
    void closeReturnsToThePreviousGuiWhenRequested() {
        var opened = new AtomicReference<String>();
        var history = new GuiHistory<String>((player, gui) -> opened.set(gui));
        var player = player();
        history.add(player, "previous");
        history.add(player, "current");

        history.handleClose(player, true);

        assertEquals("previous", opened.get());
        assertEquals("previous", history.get(player).peekLast());
    }

    @Test
    void closeClearsHistoryWhenReturningIsRejected() {
        var history = new GuiHistory<String>((player, gui) -> {
        });
        var player = player();
        history.add(player, "previous");
        history.add(player, "current");

        history.handleClose(player, false);

        assertTrue(history.get(player).isEmpty());
    }

    private static HumanEntity player() {
        var player = mock(HumanEntity.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}

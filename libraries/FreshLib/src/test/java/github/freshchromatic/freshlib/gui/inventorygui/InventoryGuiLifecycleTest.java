package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InventoryGuiLifecycleTest {

    @Test
    void concurrentViewerCloseTransitionsOnlyOneCallIntoLastViewer() throws Exception {
        var inventories = new GuiViewerRegistry();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        inventories.put(firstId, mock(Inventory.class));
        inventories.put(secondId, mock(Inventory.class));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var firstResult = CompletableFuture.supplyAsync(() -> removeViewerWhenReleased(inventories, firstId,
            inventories.get(firstId), ready, start));
        var secondResult = CompletableFuture.supplyAsync(() -> removeViewerWhenReleased(inventories, secondId,
            inventories.get(secondId), ready, start));

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        assertEquals(Set.of(GuiViewerRegistry.Removal.REMAINING_VIEWERS, GuiViewerRegistry.Removal.LAST_VIEWER), Set.of(
            firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS)));
        assertTrue(inventories.isEmpty());
    }

    private static GuiViewerRegistry.Removal removeViewerWhenReleased(GuiViewerRegistry inventories, UUID viewerId,
        Inventory inventory, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Viewer close was not released.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return inventories.remove(viewerId, inventory);
    }
}

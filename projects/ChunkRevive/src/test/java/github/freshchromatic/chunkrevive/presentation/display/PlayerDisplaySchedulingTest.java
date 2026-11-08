package github.freshchromatic.chunkrevive.presentation.display;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerDisplaySchedulingTest {

    @Test
    void markDisplayWorkIsScheduledOnEachPlayersEntityScheduler() {
        var plugin = mock(Plugin.class);
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var config = new PluginConfig();
        config.display.enabled = true;
        var service = new ChunkDisplayService(plugin, mock(MarkRegistry.class), config, mock(Messages.class), true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
            MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            assertDoesNotThrow(() -> service.onChunkMarked(new MarkedChunk("world", 0, 0, UUID.randomUUID(), 1)));

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                player)));
            verify(player, never()).getLocation();
            verify(player, never()).getWorld();
        }
    }

    @Test
    void joinDisplayWorkIsScheduledOnTheJoiningPlayersEntityScheduler() {
        var plugin = mock(Plugin.class);
        var player = mock(Player.class);
        var event = mock(PlayerJoinEvent.class);
        var service = mock(ChunkDisplayService.class);
        when(event.getPlayer()).thenReturn(player);
        var listener = new PlayerLifecycleListener(plugin, null, service);

        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            listener.onJoin(event);

            scheduler.verify(() -> Scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L), same(player)));
        }
    }

    @Test
    void yUpdatesAreScheduledOnThePlayersEntityScheduler() throws Exception {
        var plugin = mock(Plugin.class);
        var player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        var config = new PluginConfig();
        config.display.enabled = true;
        var service = new ChunkDisplayService(plugin, mock(MarkRegistry.class), config, mock(Messages.class), true);
        var state = new PlayerDisplayState(player);
        setField(service, "running", true);
        field(service, "playerDisplays").put(playerId, state);
        field(service, "cachedEyeY").put(playerId, 64.0);
        field(service, "lastUpdatedY").put(playerId, 60.0);

        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            Method tick = ChunkDisplayService.class.getDeclaredMethod("tickYUpdate");
            tick.setAccessible(true);
            tick.invoke(service);

            scheduler.verify(() -> Scheduler.runTask(eq(plugin), any(Runnable.class), any(Runnable.class), same(
                player)));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<T, Object> field(ChunkDisplayService service, String name) throws Exception {
        Field field = ChunkDisplayService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<T, Object>) field.get(service);
    }

    private static void setField(ChunkDisplayService service, String name, Object value) throws Exception {
        Field field = ChunkDisplayService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}

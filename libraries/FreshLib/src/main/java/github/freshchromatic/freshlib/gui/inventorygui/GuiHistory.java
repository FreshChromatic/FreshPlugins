package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

final class GuiHistory<T> {

    private final Map<UUID, ArrayDeque<T>>   histories = new ConcurrentHashMap<>();
    private final BiConsumer<HumanEntity, T> openPrevious;

    GuiHistory(BiConsumer<HumanEntity, T> openPrevious) {
        this.openPrevious = openPrevious;
    }

    void add(HumanEntity player, T gui) {
        histories.compute(player.getUniqueId(), (ignored, history) -> {
            ArrayDeque<T> current = history == null ? new ArrayDeque<>() : history;
            if (current.peekLast() != gui) {
                current.addLast(gui);
            }
            return current;
        });
    }

    Deque<T> get(HumanEntity player) {
        AtomicReference<Deque<T>> snapshot = new AtomicReference<>(new ArrayDeque<>());
        histories.computeIfPresent(player.getUniqueId(), (ignored, history) -> {
            snapshot.set(new ArrayDeque<>(history));
            return history;
        });
        return snapshot.get();
    }

    boolean goBack(HumanEntity player) {
        AtomicReference<T> previous = new AtomicReference<>();
        ArrayDeque<T> remaining = histories.computeIfPresent(player.getUniqueId(), (ignored, history) -> {
            history.pollLast();
            if (history.isEmpty()) {
                return null;
            }
            previous.set(history.peekLast());
            return history;
        });
        if (remaining == null) {
            return false;
        }
        T previousGui = previous.get();
        if (previousGui != null) {
            openPrevious.accept(player, previousGui);
        }
        return true;
    }

    void handleClose(HumanEntity player, boolean returnToPrevious) {
        if (returnToPrevious) {
            goBack(player);
            return;
        }
        clear(player);
    }

    Deque<T> clear(HumanEntity player) {
        Deque<T> previous = histories.remove(player.getUniqueId());
        return previous != null ? previous : new ArrayDeque<>();
    }
}

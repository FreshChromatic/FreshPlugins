package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;

final class CommandMessageRouter {

    private CommandMessageRouter() {
    }

    static boolean dispatchGlobal(Plugin plugin, Runnable action) {
        if (Scheduler.isGlobalTickThread()) {
            return false;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, action);
        return true;
    }

    static void send(Plugin plugin, Commander sender, Component message) {
        sendAll(plugin, sender, List.of(message));
    }

    static void sendAll(Plugin plugin, Commander sender, List<Component> messages) {
        List<Component> snapshot = List.copyOf(messages);
        if (sender instanceof PlayerCommander playerSender) {
            if (Scheduler.isOwnedByCurrentRegion(playerSender.player())) {
                snapshot.forEach(sender::sendMessage);
                return;
            }
            Scheduler.runTask(plugin, () -> snapshot.forEach(sender::sendMessage), playerSender.player());
            return;
        }
        if (!Scheduler.isGlobalTickThread()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> snapshot.forEach(sender::sendMessage));
            return;
        }
        snapshot.forEach(sender::sendMessage);
    }
}

package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.structure.StructureProtectionTracker;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.FreshLibCommander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.util.Components;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.bukkit.plugin.Plugin;

/**
 * No player command can instantly unmark a chunk anymore — only admin commands
 * (/cr unmark, /cr struct unblock) can. /keep and its aliases are repurposed to
 * report nearby structures' passive protection progress instead.
 */
@SuppressWarnings("UnstableApiUsage")
public final class KeepCommands {

    private Messages messages;
    private PluginConfig config;
    private final StructureProtectionTracker structureProtectionTracker;
    private final CommandManager<Commander> commandManager;

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public KeepCommands(Plugin plugin, Messages messages, PluginConfig config, StructureProtectionTracker structureProtectionTracker) {
        this.messages = messages;
        this.config = config;
        this.structureProtectionTracker = structureProtectionTracker;

        final SenderMapper<CommandSourceStack, Commander> senderMapper =
            SenderMapper.create(FreshLibCommander::from, c -> ((FreshLibCommander) c).stack());

        this.commandManager = PaperCommandManager.builder(senderMapper)
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin);
    }

    public void register() {
        for (String alias : new String[]{"keep", "keepchunk", "chunkkeep"}) {
            commandManager.command(commandManager.commandBuilder(alias)
                .permission("chunkrevive.keep")
                .senderType(PlayerCommander.class)
                .handler(this::handleKeepQuery));
        }
    }

    private void handleKeepQuery(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        var nearby = structureProtectionTracker.findNearbyTrackedGroups(
            player.getLocation(), config.structure.protection.radiusChunks);

        if (nearby.isEmpty()) {
            player.sendMessage(messages.structure.noneNearby.asComponent());
            return;
        }

        for (StructureGroup group : nearby) {
            double percent = Math.min(100.0, group.protectionTicks() * 100.0
                / config.structure.protection.requiredTicks);
            long remainingTicks = Math.max(0, config.structure.protection.requiredTicks - group.protectionTicks());
            player.sendMessage(messages.structure.protectionProgress.withPlaceholders(
                Components.placeholder("percent", "%.1f".formatted(percent)),
                Components.placeholder("remaining", formatTicks(remainingTicks))));
        }
    }

    private String formatTicks(long ticks) {
        long seconds = ticks / 20L;
        if (seconds < 60) return messages.text("duration-seconds", seconds);
        if (seconds < 3600) return messages.text("duration-minutes", seconds / 60);
        return messages.text("duration-hours", seconds / 3600);
    }
}

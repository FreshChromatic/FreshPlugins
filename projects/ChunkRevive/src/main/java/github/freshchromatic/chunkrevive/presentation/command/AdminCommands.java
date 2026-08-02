package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;

import java.util.Optional;

/** Handles administrative operations that do not belong to a feature command group. */
public final class AdminCommands {
    private final ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private final DiskChunkScanner scanner;
    private final DeletionService deletionService;
    private Messages messages;

    public AdminCommands(
            ChunkRevivePlugin plugin,
            MarkRegistry markRegistry,
            DiskChunkScanner scanner,
            DeletionService deletionService,
            Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.scanner = scanner;
        this.deletionService = deletionService;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void cancel(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        var queue = markRegistry.getRegenerationQueue();
        boolean cancelledSomething = false;

        if (queue.isRunning()) {
            queue.cancel();
            sender.sendMessage(Component.text(messages.text("regen-cancel-requested")).color(AdminTuiBuilder.NORMAL));
            cancelledSomething = true;
        }
        if (scanner.isRunning()) {
            scanner.cancel();
            sender.sendMessage(Component.text(messages.text("scan-cancel-requested")).color(AdminTuiBuilder.NORMAL));
            cancelledSomething = true;
        }
        // SQLite may be committing a deletion batch. Never wait for that transaction on Folia's
        // global-region thread; cancellation persistence is completed on a virtual thread instead.
        boolean cancelledOtherWork = cancelledSomething;
        Thread.ofVirtual().name("cr-cancel-deletions").start(() -> {
            int deletionJobs = deletionService.cancelWaiting();
            if (deletionJobs > 0) {
                sender.sendMessage(messages.deletion.cancelled.withPlaceholders(
                    Components.placeholder("count", String.valueOf(deletionJobs))));
            } else if (!cancelledOtherWork) {
                sender.sendMessage(Component.text(messages.text("no-cancellable-work"))
                    .color(AdminTuiBuilder.SECONDARY));
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    public void reload(CommandContext<Commander> ctx) {
        plugin.reload();
        ctx.sender().sendMessage(messages.command.reloadSuccess.asComponent());
    }

    public void regionsCount(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        Optional<String> worldOpt = ctx.optional("world");
        String worldName;
        if (worldOpt.isPresent()) {
            worldName = worldOpt.get();
        } else if (sender instanceof PlayerCommander pc) {
            worldName = pc.player().getWorld().getName();
        } else {
            sender.sendMessage(Component.text(messages.text("console-world-required")).color(AdminTuiBuilder.SEVERE));
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }

        int count = scanner.countRegionFiles(world, null);
        sender.sendMessage(messages.regions.count.withPlaceholders(
            Components.placeholder("world", worldName),
            Components.placeholder("count", String.valueOf(count))
        ));
    }


}

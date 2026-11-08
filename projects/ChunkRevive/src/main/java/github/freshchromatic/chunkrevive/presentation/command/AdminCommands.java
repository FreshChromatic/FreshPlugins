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

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.Optional;
import java.util.function.Consumer;

/** Handles administrative operations that do not belong to a feature command group. */
public final class AdminCommands {

    private final ChunkRevivePlugin                                    plugin;
    private final MarkRegistry                                         markRegistry;
    private final DiskChunkScanner                                     scanner;
    private final DeletionService                                      deletionService;
    private final Consumer<List<DeletionService.CancelledDeletionJob>> deletionCancellation;
    private Messages                                                   messages;

    public AdminCommands(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DiskChunkScanner scanner,
        DeletionService deletionService, Messages messages) {
        this(plugin, markRegistry, scanner, deletionService, messages, ignored -> {
        });
    }

    public AdminCommands(ChunkRevivePlugin plugin, MarkRegistry markRegistry, DiskChunkScanner scanner,
        DeletionService deletionService, Messages messages,
        Consumer<List<DeletionService.CancelledDeletionJob>> deletionCancellation) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.scanner = scanner;
        this.deletionService = deletionService;
        this.deletionCancellation = deletionCancellation;
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
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("regen-cancel-requested")).color(
                AdminTuiBuilder.NORMAL));
            cancelledSomething = true;
        }
        if (scanner.isRunning()) {
            scanner.cancel();
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("scan-cancel-requested")).color(
                AdminTuiBuilder.NORMAL));
            cancelledSomething = true;
        }
        // SQLite may be committing a deletion batch. Never wait for that transaction on Folia's
        // global-region thread; cancellation persistence is completed on a virtual thread instead.
        boolean cancelledOtherWork = cancelledSomething;
        Thread.ofVirtual()
            .name("cr-cancel-deletions")
            .start(() -> {
                int deletionJobs;
                try {
                    List<DeletionService.CancelledDeletionJob> cancelledJobs = deletionService.cancelWaitingWithIds(deletionCancellation);
                    deletionJobs = cancelledJobs.size();
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("Failed to cancel deletion jobs: " + failure.getMessage());
                    CommandMessageRouter.send(plugin, sender, Component.text(messages.text("deletion-cancel-failed", failure.getMessage())).color(AdminTuiBuilder.SEVERE));
                    return;
                }
                if (deletionJobs > 0) {
                    CommandMessageRouter.send(plugin, sender, messages.deletion.cancelled.withPlaceholders(Components.placeholder("count", String.valueOf(deletionJobs))));
                } else if (!cancelledOtherWork) {
                    CommandMessageRouter.send(plugin, sender, Component.text(messages.text("no-cancellable-work")).color(AdminTuiBuilder.SECONDARY));
                }
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public void reload(CommandContext<Commander> ctx) {
        boolean success = plugin.tryReload();
        CommandMessageRouter.send(plugin, ctx.sender(), (success ? messages.command.reloadSuccess
            : messages.command.reloadFailed).asComponent());
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
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("console-world-required")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        if (CommandMessageRouter.dispatchGlobal(plugin, () -> regionsCount(sender, worldName))) {
            return;
        }
        regionsCount(sender, worldName);
    }

    private void regionsCount(Commander sender, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        scanner.countRegionFilesAsync(world, null).whenComplete((count, failure) -> {
            if (failure != null) {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (!(cause instanceof UncheckedIOException)) {
                    plugin.getLogger().warning("Failed to count region files: " + cause.getMessage());
                }
                CommandMessageRouter.send(plugin, sender, messages.scan.scanFailed.withPlaceholders(
                    Components.placeholder("reason", cause.getMessage() == null ? cause.getClass().getSimpleName()
                        : cause.getMessage())));
                return;
            }
            CommandMessageRouter.send(plugin, sender, messages.regions.count.withPlaceholders(Components.placeholder(
                "world", worldName), Components.placeholder("count", String.valueOf(count))));
        });
    }

}

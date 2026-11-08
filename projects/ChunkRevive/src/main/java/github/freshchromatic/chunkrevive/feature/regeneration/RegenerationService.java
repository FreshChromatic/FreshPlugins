package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.nms.ChunkArea;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.feature.coordination.ChunkMutationCoordinator;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

public final class RegenerationService {

    private Messages                       messages;
    private final LandProtection           landProtection;
    private final ProtectionChecks         protectionChecks;
    private final Plugin                   plugin;
    private final EndPodiumRestorer        endPodiumRestorer;
    private final ChunkMutationCoordinator chunkMutationCoordinator;

    public RegenerationService(Plugin plugin, Messages messages, LandProtection landProtection,
        ProtectionChecks protectionChecks) {
        this(plugin, messages, landProtection, protectionChecks, new ChunkMutationCoordinator());
    }

    public RegenerationService(Plugin plugin, Messages messages, LandProtection landProtection,
        ProtectionChecks protectionChecks, ChunkMutationCoordinator chunkMutationCoordinator) {
        this.messages = messages;
        this.landProtection = landProtection;
        this.protectionChecks = protectionChecks;
        this.plugin = plugin;
        this.chunkMutationCoordinator = chunkMutationCoordinator;
        this.endPodiumRestorer = new EndPodiumRestorer(plugin);
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk) {
        return regenChunks(notifyTarget, Collections.singletonList(chunk), false);
    }

    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk, boolean silent) {
        return regenChunks(notifyTarget, Collections.singletonList(chunk), silent);
    }

    /**
     * @param extraContextBounds optional inclusive chunk area widening
     *                            the read-only context window — see {@link NmsTerrainGenerator#generate}.
     *                            Lets a single-chunk regen of a chunk inside a known structure still see
     *                            that structure's StructureStart without regenerating the whole structure.
     */
    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk, ChunkArea extraContextBounds) {
        return regenChunks(notifyTarget, Collections.singletonList(chunk), false, () -> false, extraContextBounds);
    }

    public CompletableFuture<Void> regenChunks(Audience notifyTarget, Collection<MarkedChunk> chunks, boolean silent) {
        return regenChunks(notifyTarget, chunks, silent, () -> false);
    }

    /**
     * @param isCancelled checked throughout generation (before/inside each parallel pass, and once more
     *                     before the disk write begins) so a batch still in the generation phase can be
     *                     aborted promptly by {@code /cr cancel} or plugin shutdown. Once the disk write
     *                     itself starts there is no further cancellation point — by design, since aborting
     *                     mid-write would risk a half-written chunk.
     */
    public CompletableFuture<Void> regenChunks(Audience notifyTarget, Collection<MarkedChunk> chunks, boolean silent,
        BooleanSupplier isCancelled) {
        return regenChunks(notifyTarget, chunks, silent, isCancelled, null);
    }

    public CompletableFuture<Void> regenChunks(Audience notifyTarget, Collection<MarkedChunk> chunks, boolean silent,
        BooleanSupplier isCancelled, ChunkArea extraContextBounds) {
        if (chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var targets = List.copyOf(chunks);
        var keys = targets.stream().map(chunk -> new ChunkKey(chunk.world(), chunk.cx(), chunk.cz())).toList();
        var result = new CompletableFuture<Void>();
        onGlobal(result, () -> {
            long revision = protectionChecks.revision();
            try {
                protectionChecks.check(MaintenanceAction.REGENERATE, keys).whenComplete((decision, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    onGlobal(result, () -> {
                        if (isCancelled.getAsBoolean()) {
                            result.cancel(false);
                        } else if (revision != protectionChecks.revision() || denied(decision, keys)) {
                            result.completeExceptionally(new SkipChunkException("Land protection"));
                        } else {
                            try {
                                regenerateChecked(notifyTarget, targets, keys, silent, isCancelled, revision,
                                    extraContextBounds).whenComplete((ignored, error) -> {
                                        if (error == null) {
                                            result.complete(null);
                                        } else {
                                            result.completeExceptionally(error);
                                        }
                                    });
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        }
                    });
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void onGlobal(CompletableFuture<?> result, Runnable task) {
        try {
            if (!plugin.isEnabled()) {
                result.cancel(false);
                return;
            }
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    task.run();
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static boolean denied(ProtectionBatchResult result, List<ChunkKey> chunks) {
        return result == null || chunks.stream().anyMatch(chunk -> result.decisions().getOrDefault(chunk,
            ProtectionDecision.ABSTAIN) == ProtectionDecision.DENY);
    }

    private CompletableFuture<Void> regenerateChecked(Audience notifyTarget, Collection<MarkedChunk> chunks,
        List<ChunkKey> keys, boolean silent, BooleanSupplier isCancelled, long protectionRevision,
        ChunkArea extraContextBounds) {

        MarkedChunk first = chunks.iterator().next();
        World world = Bukkit.getWorld(first.world());
        if (world == null) {
            sendMessage(notifyTarget, messages.regen.failed.withPlaceholders(Components.placeholder("cx_cz", "Group"),
                Components.placeholder("reason", "World not found: " + first.world())));
            return CompletableFuture.failedFuture(new SkipChunkException("World not found: " + first.world()));
        }

        for (MarkedChunk chunk : chunks) {
            if (landProtection.hasClaim(world, chunk.cx(), chunk.cz())) {
                sendMessage(notifyTarget, messages.regen.residenceBlocked.withPlaceholders(Components.placeholder(
                    "cx_cz", chunk.coordDisplay())));
                var future = new CompletableFuture<Void>();
                future.completeExceptionally(new SkipChunkException("Land protection"));
                return future;
            }
        }

        return chunkMutationCoordinator.run(keys, () -> regenerateLocked(notifyTarget, chunks, first, world, silent,
            isCancelled, protectionRevision, extraContextBounds));
    }

    private CompletableFuture<Void> regenerateLocked(Audience notifyTarget, Collection<MarkedChunk> chunks,
        MarkedChunk first, World world, boolean silent, BooleanSupplier isCancelled, long protectionRevision,
        ChunkArea extraContextBounds) {
        if (isCancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new CancellationException("Terrain generation cancelled"));
        }
        if (protectionRevision != protectionChecks.revision()) {
            return CompletableFuture.failedFuture(new SkipChunkException("Protection changed"));
        }

        if (!silent) {
            if (chunks.size() == 1) {
                sendMessage(notifyTarget, messages.regen.start.withPlaceholders(Components.placeholder("cx_cz",
                    first.coordDisplay())));
            } else {
                sendMessage(notifyTarget, messages.regen.batchStart.withPlaceholders(Components.placeholder("count",
                    String.valueOf(chunks.size()))));
            }
        }

        long seed = world.getSeed();
        String[] currentStage = { "" };
        long startTime = System.currentTimeMillis();

        List<ChunkCoordinate> centerChunks = new ArrayList<>();
        for (MarkedChunk chunk : chunks) {
            centerChunks.add(new ChunkCoordinate(chunk.cx(), chunk.cz()));
        }

        BooleanSupplier cancelledOrChanged = () -> isCancelled.getAsBoolean()
            || protectionRevision != protectionChecks.revision();
        return NmsTerrainGenerator.generate(world, centerChunks, seed, percent -> {
            // Throttle to 0%, 25%, 50%, 75%, 100% to avoid chat spam
            if (!silent && percent % 25 == 0) {
                sendMessage(notifyTarget, messages.regen.progress.withPlaceholders(Components.placeholder("cx_cz", chunks.size() == 1 ? first.coordDisplay() : "Group"), Components.placeholder("percent", String.valueOf(percent)), Components.placeholder("stage", currentStage[0])));
            }
        }, stage -> currentStage[0] = stage, cancelledOrChanged, extraContextBounds)
            .whenComplete((v, ex) -> {
                if (ex != null) {
                    Throwable cause = ex;
                    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                        && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (isProtectionChangeCancellation(cause, isCancelled, protectionRevision)) {
                        return;
                    }
                    Logging.logger().severe("Regen failed for group of " + chunks.size() + " chunks", cause);
                    sendMessage(notifyTarget, messages.regen.failed.withPlaceholders(Components.placeholder("cx_cz", chunks.size() == 1 ? first.coordDisplay() : "Group"), Components.placeholder("reason", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())));
                } else {
                    endPodiumRestorer.restoreIfAffected(world, chunks);
                    if (!silent) {
                        long elapsedMs = System.currentTimeMillis() - startTime;
                        if (chunks.size() == 1) {
                            sendMessage(notifyTarget, messages.regen.done.withPlaceholders(Components.placeholder("cx_cz", first.coordDisplay()), Components.placeholder("elapsed", formatDuration(elapsedMs)),
                                Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
                        } else {
                            sendMessage(notifyTarget, messages.regen.done.withPlaceholders(Components.placeholder("cx_cz", "Group"), Components.placeholder("elapsed", formatDuration(elapsedMs)),
                                Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
                        }
                    }
                }
            })
            .exceptionallyCompose(failure -> isProtectionChangeCancellation(failure, isCancelled, protectionRevision)
            ? CompletableFuture.failedFuture(new SkipChunkException("Protection changed"))
            : CompletableFuture.failedFuture(failure));
    }

    private boolean isProtectionChangeCancellation(Throwable failure, BooleanSupplier isCancelled,
        long protectionRevision) {
        if (isCancelled.getAsBoolean() || protectionRevision == protectionChecks.revision()) {
            return false;
        }
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof CancellationException;
    }

    void sendMessage(Audience target, Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            if (target instanceof PlayerCommander commander) {
                Player player = commander.player();
                Scheduler.runTask(plugin, () -> target.sendMessage(message), () -> {
                }, player);
            } else if (target instanceof Player player) {
                Scheduler.runTask(plugin, () -> target.sendMessage(message), () -> {
                }, player);
            } else {
                Scheduler.runTask(plugin, () -> target.sendMessage(message));
            }
        } catch (RuntimeException failure) {
            Logging.logger().warning("Could not schedule regeneration notification: " + failure.getMessage());
        }
    }

    /** Formats a millisecond duration as e.g. "1分5秒", "12.3秒", or "850ms" for chat messages. */
    public String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return messages.text("regen-duration-minutes-seconds", minutes, seconds);
        }
        return messages.text("regen-duration-seconds", ms / 1000.0);
    }
}

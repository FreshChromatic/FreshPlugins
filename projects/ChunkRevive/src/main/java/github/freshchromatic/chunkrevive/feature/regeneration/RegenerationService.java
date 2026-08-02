package github.freshchromatic.chunkrevive.feature.regeneration;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class RegenerationService {

    private Messages messages;
    private final LandProtection landProtection;

    public RegenerationService(Messages messages, LandProtection landProtection) {
        this.messages = messages;
        this.landProtection = landProtection;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk) {
        return regenChunks(notifyTarget, java.util.Collections.singletonList(chunk), false);
    }

    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk, boolean silent) {
        return regenChunks(notifyTarget, java.util.Collections.singletonList(chunk), silent);
    }

    /**
     * @param extraContextBounds optional [minChunkX, maxChunkX, minChunkZ, maxChunkZ] (inclusive) widening
     *                            the read-only context window — see {@link NmsTerrainGenerator#generate}.
     *                            Lets a single-chunk regen of a chunk inside a known structure still see
     *                            that structure's StructureStart without regenerating the whole structure.
     */
    public CompletableFuture<Void> regenChunk(Audience notifyTarget, MarkedChunk chunk, int[] extraContextBounds) {
        return regenChunks(notifyTarget, java.util.Collections.singletonList(chunk), false, () -> false, extraContextBounds);
    }

    public CompletableFuture<Void> regenChunks(Audience notifyTarget, java.util.Collection<MarkedChunk> chunks, boolean silent) {
        return regenChunks(notifyTarget, chunks, silent, () -> false);
    }

    /**
     * @param isCancelled checked throughout generation (before/inside each parallel pass, and once more
     *                     before the disk write begins) so a batch still in the generation phase can be
     *                     aborted promptly by {@code /cr cancel} or plugin shutdown. Once the disk write
     *                     itself starts there is no further cancellation point — by design, since aborting
     *                     mid-write would risk a half-written chunk.
     */
    public CompletableFuture<Void> regenChunks(Audience notifyTarget, java.util.Collection<MarkedChunk> chunks,
                                                boolean silent, BooleanSupplier isCancelled) {
        return regenChunks(notifyTarget, chunks, silent, isCancelled, null);
    }

    public CompletableFuture<Void> regenChunks(Audience notifyTarget, java.util.Collection<MarkedChunk> chunks,
                                                boolean silent, BooleanSupplier isCancelled, int[] extraContextBounds) {
        if (chunks.isEmpty()) return CompletableFuture.completedFuture(null);

        MarkedChunk first = chunks.iterator().next();
        World world = Bukkit.getWorld(first.world());
        if (world == null) {
            notifyTarget.sendMessage(messages.regen.failed.withPlaceholders(
                Components.placeholder("cx_cz", "Group"),
                Components.placeholder("reason", "World not found: " + first.world())));
            return CompletableFuture.completedFuture(null);
        }

        for (MarkedChunk chunk : chunks) {
            if (landProtection.hasClaim(world, chunk.cx(), chunk.cz())) {
                notifyTarget.sendMessage(messages.regen.residenceBlocked.withPlaceholders(
                    Components.placeholder("cx_cz", chunk.coordDisplay())));
                var future = new CompletableFuture<Void>();
                future.completeExceptionally(new SkipChunkException("Residence"));
                return future;
            }
        }

        if (!silent) {
            if (chunks.size() == 1) {
                notifyTarget.sendMessage(messages.regen.start.withPlaceholders(
                    Components.placeholder("cx_cz", first.coordDisplay())));
            } else {
                notifyTarget.sendMessage(messages.regen.batchStart.withPlaceholders(
                    Components.placeholder("count", String.valueOf(chunks.size()))));
            }
        }

        long seed = world.getSeed();
        String[] currentStage = {""};
        long startTime = System.currentTimeMillis();

        java.util.List<ChunkCoordinate> centerChunks = new java.util.ArrayList<>();
        for (MarkedChunk chunk : chunks) {
            centerChunks.add(new ChunkCoordinate(chunk.cx(), chunk.cz()));
        }

        return NmsTerrainGenerator.generate(
            world,
            centerChunks,
            seed,
            percent -> {
                // Throttle to 0%, 25%, 50%, 75%, 100% to avoid chat spam
                if (!silent && percent % 25 == 0) {
                    notifyTarget.sendMessage(messages.regen.progress.withPlaceholders(
                        Components.placeholder("cx_cz", chunks.size() == 1 ? first.coordDisplay() : "Group"),
                        Components.placeholder("percent", String.valueOf(percent)),
                        Components.placeholder("stage", currentStage[0])));
                }
            },
            stage -> currentStage[0] = stage,
            isCancelled,
            extraContextBounds
        ).whenComplete((v, ex) -> {
            if (ex != null) {
                Throwable cause = ex;
                while ((cause instanceof java.util.concurrent.CompletionException
                    || cause instanceof java.util.concurrent.ExecutionException)
                    && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                Logging.logger().severe("Regen failed for group of " + chunks.size() + " chunks", cause);
                notifyTarget.sendMessage(messages.regen.failed.withPlaceholders(
                    Components.placeholder("cx_cz", chunks.size() == 1 ? first.coordDisplay() : "Group"),
                    Components.placeholder("reason", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())));
            } else {
                if (!silent) {
                    long elapsedMs = System.currentTimeMillis() - startTime;
                    if (chunks.size() == 1) {
                        notifyTarget.sendMessage(messages.regen.done.withPlaceholders(
                            Components.placeholder("cx_cz", first.coordDisplay()),
                            Components.placeholder("elapsed", formatDuration(elapsedMs)),
                            Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
                    } else {
                        notifyTarget.sendMessage(messages.regen.done.withPlaceholders(
                            Components.placeholder("cx_cz", "Group"),
                            Components.placeholder("elapsed", formatDuration(elapsedMs)),
                            Components.placeholder("elapsed_ms", String.valueOf(elapsedMs))));
                    }
                }
            }
        });
    }

    /** Formats a millisecond duration as e.g. "1分5秒", "12.3秒", or "850ms" for chat messages. */
    public String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return messages.text("regen-duration-minutes-seconds", minutes, seconds);
        return messages.text("regen-duration-seconds", ms / 1000.0);
    }
}

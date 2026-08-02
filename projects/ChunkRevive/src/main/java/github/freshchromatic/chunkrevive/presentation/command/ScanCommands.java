package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.presentation.command.ConfirmationManager;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Handles persisted-chunk scans and biome-based marking/regeneration. */
public final class ScanCommands {
    private record BiomeSelection(String biomeId, List<MarkedChunk> chunks, boolean truncated) {}
    private final ChunkRevivePlugin plugin;
    private final WorldAccessPolicy worldAccessPolicy;
    private final ChunkScanService chunkScanService;
    private final BiConsumer<Commander, Collection<MarkedChunk>> bulkReset;
    private final ConfirmationManager confirmationManager;
    private final DeletionService deletionService;
    private Messages messages;

    public ScanCommands(
            ChunkRevivePlugin plugin,
            WorldAccessPolicy worldAccessPolicy,
            ChunkScanService chunkScanService,
            BiConsumer<Commander, Collection<MarkedChunk>> bulkReset,
            ConfirmationManager confirmationManager,
            DeletionService deletionService,
            Messages messages) {
        this.plugin = plugin;
        this.worldAccessPolicy = worldAccessPolicy;
        this.chunkScanService = chunkScanService;
        this.bulkReset = bulkReset;
        this.confirmationManager = confirmationManager;
        this.deletionService = deletionService;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void fullMark(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        List<World> worlds = "all".equalsIgnoreCase(worldName)
            ? Bukkit.getWorlds().stream()
                .filter(world -> worldAccessPolicy.isAllowed(world.getName(), WorldAccessPolicy.Scope.BULK_MARK))
                .toList()
            : Optional.ofNullable(Bukkit.getWorld(worldName)).stream().toList();
        if (worlds.isEmpty()) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (!"all".equalsIgnoreCase(worldName) && !worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }
        if (!"all".equalsIgnoreCase(worldName) && chunkScanService.isRunning(worldName)) {
            sender.sendMessage(messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!plugin.getPluginConfig().scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            sender.sendMessage(messages.scan.regenRunning.asComponent());
            return;
        }

        if (confirmed) {
            for (World world : worlds) {
                if (chunkScanService.isRunning(world.getName())) {
                    sender.sendMessage(Component.text(messages.text("scan-already-running-world", world.getName())).color(AdminTuiBuilder.SECONDARY));
                    continue;
                }
                startScan(sender, world, null, null);
            }
            return;
        }

        var cfg = plugin.getPluginConfig();
        int regionCount = worlds.stream().mapToInt(world -> chunkScanService.countRegions(world, null)).sum();
        sender.sendMessage(messages.scan.confirmRequired.withPlaceholders(
            Components.placeholder("world", worldName),
            Components.placeholder("region_count", String.valueOf(regionCount)),
            Components.placeholder("timeout", String.valueOf(cfg.safety.confirmTimeoutSeconds)),
            Components.placeholder("command", "/cr mark fullmark " + worldName)));
    }

    public void radiusMark(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        int radius = ctx.get("radius");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }

        var cfg = plugin.getPluginConfig();
        if (radius > cfg.scan.radiusmarkMaxRadiusChunks) {
            sender.sendMessage(messages.scan.radiusTooLarge.withPlaceholders(
                Components.placeholder("radius", String.valueOf(radius)),
                Components.placeholder("max", String.valueOf(cfg.scan.radiusmarkMaxRadiusChunks))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            sender.sendMessage(messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!cfg.scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            sender.sendMessage(messages.scan.regenRunning.asComponent());
            return;
        }

        Optional<Integer> xOpt = ctx.optional("x");
        Optional<Integer> zOpt = ctx.optional("z");
        int blockX, blockZ;
        if (xOpt.isPresent() && zOpt.isPresent()) {
            blockX = xOpt.get();
            blockZ = zOpt.get();
        } else if (sender instanceof PlayerCommander playerCommander) {
            var loc = playerCommander.player().getLocation();
            blockX = loc.getBlockX();
            blockZ = loc.getBlockZ();
        } else {
            var spawn = world.getSpawnLocation();
            blockX = spawn.getBlockX();
            blockZ = spawn.getBlockZ();
        }

        var area = new DiskChunkScanner.ScanArea(blockX >> 4, blockZ >> 4, radius);
        startScan(sender, world, area, null);
    }

    private void startScan(Commander sender, World world, DiskChunkScanner.ScanArea area, DiskChunkScanner.BiomeFilter biomeFilter) {
        sender.sendMessage(messages.scan.scanStarted.withPlaceholders(Components.placeholder("world", world.getName())));
        chunkScanService.scan(world, area, biomeFilter).whenComplete((result, ex) -> {
            if (ex != null) {
                sender.sendMessage(messages.scan.scanFailed.withPlaceholders(
                    Components.placeholder("reason", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())));
                return;
            }
            sender.sendMessage(messages.scan.scanComplete.withPlaceholders(
                Components.placeholder("found", String.valueOf(result.chunksFound())),
                Components.placeholder("marked", String.valueOf(result.chunksMarked())),
                Components.placeholder("skipped_existing", String.valueOf(result.chunksSkippedExisting())),
                Components.placeholder("skipped_claimed", String.valueOf(result.chunksSkippedClaimed())),
                Components.placeholder("skipped_biome_mismatch", String.valueOf(result.chunksSkippedBiomeMismatch()))));
        });
    }

    // ── biomeradius / biomefull ─────────────────────────────────────────────

    /**
     * Resolves the special "here" keyword to the real biome id at the sender's current location
     * (so a player can mark "whatever biome I'm standing in" without typing its id); any other
     * input is returned unchanged for {@link #parseBiomeIds} to handle. Returns null if "here" was
     * used but the sender isn't a player standing in the target world.
     */
    private String resolveBiomeIdsArg(Commander sender, World world, String raw) {
        if (!raw.trim().equalsIgnoreCase("here")) {
            return raw;
        }
        if (!(sender instanceof PlayerCommander pc) || !pc.player().getWorld().getName().equals(world.getName())) {
            return null;
        }
        // Sampled at the chunk's center (BiomeMatcher.centerBiome), not the player's exact block
        // position: biome resolution is quart (4-block) granularity, finer than a chunk, so the
        // corner of a chunk a player happens to stand on can genuinely differ from that chunk's
        // center — sampling the exact position here would disagree with how BiomeMatcher.matches()
        // tests that same chunk under MatchMode.CENTER, making the player's own starting chunk
        // fail to match itself.
        var loc = pc.player().getLocation();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        return chunkScanService.centerBiome(world, cx, cz);
    }

    private ChunkScanService.BiomeIds parseBiomeIds(World world, String raw) {
        return chunkScanService.parseBiomeIds(world, raw);
    }

    public void biomeFullMark(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        String biomeIdsRaw = ctx.get("biomeIds");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }

        String resolvedBiomeIds = resolveBiomeIdsArg(sender, world, biomeIdsRaw);
        if (resolvedBiomeIds == null) {
            sender.sendMessage(Component.text(messages.text("here-biome-requires-player", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        biomeIdsRaw = resolvedBiomeIds;

        var targets = parseBiomeIds(world, biomeIdsRaw);
        if (!targets.invalid().isEmpty()) {
            sender.sendMessage(messages.scan.unknownBiome.withPlaceholders(
                Components.placeholder("ids", String.join(", ", targets.invalid()))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            sender.sendMessage(messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!plugin.getPluginConfig().scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            sender.sendMessage(messages.scan.regenRunning.asComponent());
            return;
        }

        var filter = new DiskChunkScanner.BiomeFilter(targets.resolved(), plugin.getPluginConfig().biome.matchModeEnum());
        if (confirmed) {
            startScan(sender, world, null, filter);
            return;
        }

        var cfg = plugin.getPluginConfig();
        int regionCount = chunkScanService.countRegions(world, null);
        sender.sendMessage(messages.scan.biomeConfirmRequired.withPlaceholders(
            Components.placeholder("world", worldName),
            Components.placeholder("biomes", biomeIdsRaw),
            Components.placeholder("region_count", String.valueOf(regionCount)),
            Components.placeholder("timeout", String.valueOf(cfg.safety.confirmTimeoutSeconds)),
            Components.placeholder("command", "/cr mark biomefull " + worldName + " " + biomeIdsRaw)));
    }

    public void biomeRadiusMark(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        String biomeIdsRaw = ctx.get("biomeIds");
        int radius = ctx.get("radius");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }

        Integer xOpt = ctx.<Integer>optional("x").orElse(null);
        Integer zOpt = ctx.<Integer>optional("z").orElse(null);
        doBiomeRadiusMark(sender, world, biomeIdsRaw, radius, xOpt, zOpt);
    }

    /** Players-only convenience: marks the biome the player is currently standing in within <radius> chunks. */
    /** Players-only convenience: marks the whole contiguous biome patch the player is currently standing in. */
    public void markHereBiome(CommandContext<PlayerCommander> ctx) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        String worldName = world.getName();
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var cfg = plugin.getPluginConfig();
            var toMark = buildMarkedChunksFromDetected(world, detected.result().chunks());
            if (toMark.isEmpty()) {
                sender.sendMessage(Component.text(messages.text("biome-no-markable-chunks", detected.biomeId()))
                    .color(AdminTuiBuilder.SEVERE));
                return;
            }
            var newlyAdded = chunkScanService.markCandidates(toMark);
            String truncated = detected.result().truncated()
                ? messages.text("biome-mark-truncated", cfg.biome.regen.floodFillMaxChunks) : "";
            sender.sendMessage(Component.text(messages.text("biome-marked", detected.biomeId(), newlyAdded.size(), toMark.size(), truncated))
                .color(AdminTuiBuilder.NORMAL));
        });
    }

    /**
     * Resolves the biome at the sender's current location and flood-fills outward to find the whole
     * contiguous, already-on-disk patch (see {@link BiomeRegionService}). The flood fill is blocking
     * disk I/O, so it runs on a virtual thread; the returned future always completes normally — on any
     * validation failure it messages the sender itself and completes with {@code null}.
     */
    private CompletableFuture<ChunkScanService.BiomeDetection> runHereBiomeDetection(Commander sender, World world) {
        if (!(sender instanceof PlayerCommander pc)) {
            sender.sendMessage(Component.text(messages.text("player-only")).color(AdminTuiBuilder.SEVERE));
            return CompletableFuture.completedFuture(null);
        }
        String resolvedBiomeId = resolveBiomeIdsArg(sender, world, "here");
        if (resolvedBiomeId == null) {
            sender.sendMessage(Component.text(messages.text("biome-undetermined")).color(AdminTuiBuilder.SEVERE));
            return CompletableFuture.completedFuture(null);
        }
        var targets = parseBiomeIds(world, resolvedBiomeId);
        if (!targets.invalid().isEmpty()) {
            sender.sendMessage(messages.scan.unknownBiome.withPlaceholders(
                Components.placeholder("ids", String.join(", ", targets.invalid()))));
            return CompletableFuture.completedFuture(null);
        }

        var loc = pc.player().getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        return chunkScanService.detectBiomeRegion(world, cx, cz, resolvedBiomeId);
    }

    /** Returns background detection results to the player's owning region before touching Bukkit state. */
    private void onHereBiomeDetected(PlayerCommander sender, World world,
                                     Consumer<ChunkScanService.BiomeDetection> action) {
        runHereBiomeDetection(sender, world).thenAccept(detected -> {
            if (detected == null) return;
            Scheduler.runTask(plugin, () -> {
                if (sender.player().isOnline()) action.accept(detected);
            }, sender.player());
        });
    }

    /** Converts detected chunk coordinates into fresh biome-tagged MarkedChunks, excluding Residence claims. */
    private List<MarkedChunk> buildMarkedChunksFromDetected(World world, List<ChunkCoordinate> chunks) {
        return chunkScanService.buildBiomeCandidates(world, chunks);
    }

    /**
     * Players-only convenience mirroring /cr regen here struct: flood-fills the biome the player is
     * standing in (see {@link BiomeRegionService}), then immediately regenerates whatever was found —
     * combining /cr mark here biome + /cr regen all chunks into one step.
     *
     * <p>Unlike /cr mark here biome (marking alone is never gated), this skips straight to a regen, so
     * it reuses the same safety.bulk-regen-confirm-threshold-chunks confirmation /cr regen all uses:
     * detection always runs (cheap, read-only), but if the matched count exceeds the threshold the
     * mark+regen itself waits for --confirm instead of firing immediately.
     */
    public void regenerateHereBiome(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        String worldName = world.getName();

        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.REGEN)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", worldName)));
            return;
        }
        if (chunkScanService.isRegenerationRunning()) {
            sender.sendMessage(Component.text(messages.text("regen-busy")).color(AdminTuiBuilder.SEVERE));
            return;
        }

        String confirmationKey = "regen-here-biome:" + worldName;
        if (confirmed) {
            var cached = confirmationManager.peekPayload(sender.commanderId(), confirmationKey);
            if (cached.isEmpty() || !(cached.get() instanceof BiomeSelection selection)
                    || !confirmationManager.confirm(sender.commanderId(), confirmationKey)) {
                sender.sendMessage(messages.scan.confirmExpired.asComponent());
                return;
            }
            executeBiomeRegeneration(sender, selection);
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var cfg = plugin.getPluginConfig();
            var toMark = buildMarkedChunksFromDetected(world, detected.result().chunks());
            if (toMark.isEmpty()) {
                sender.sendMessage(Component.text(messages.text("biome-no-regenerable-chunks", detected.biomeId()))
                    .color(AdminTuiBuilder.SEVERE));
                return;
            }

            int threshold = cfg.safety.bulkRegenConfirmThresholdChunks;
            var selection = new BiomeSelection(
                detected.biomeId(), List.copyOf(toMark), detected.result().truncated());
            if (toMark.size() > threshold) {
                confirmationManager.request(sender.commanderId(), confirmationKey,
                    cfg.safety.confirmTimeoutSeconds, selection);
                String truncated = detected.result().truncated()
                    ? messages.text("biome-regen-truncated", cfg.biome.regen.floodFillMaxChunks) : "";
                sender.sendMessage(Component.text(messages.text("biome-regen-confirm", detected.biomeId(), toMark.size(), truncated, cfg.safety.confirmTimeoutSeconds))
                    .color(TextColor.color(0xFFAA00)));
                return;
            }
            executeBiomeRegeneration(sender, selection);
        });
    }

    private void executeBiomeRegeneration(PlayerCommander sender, BiomeSelection selection) {
        sender.sendMessage(Component.text(messages.text(
            "biome-regen-start", selection.biomeId(), selection.chunks().size()))
            .color(AdminTuiBuilder.NORMAL));
        chunkScanService.markDirect(selection.chunks());
        bulkReset.accept(sender, selection.chunks());
    }

    public void resetHereBiome(CommandContext<PlayerCommander> ctx, boolean confirmed,
                               ResetCommands resetCommands) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        String worldName = world.getName();

        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.REGEN)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        String confirmationKey = "reset-here-biome:" + worldName;
        if (confirmed) {
            var cached = confirmationManager.peekPayload(sender.commanderId(), confirmationKey);
            if (cached.isEmpty() || !(cached.get() instanceof BiomeSelection selection)) {
                sender.sendMessage(messages.scan.confirmExpired.asComponent());
                return;
            }
            resetCommands.resetDetectedBiome(sender, selection.chunks(), true, confirmationKey,
                selection.biomeId(), selection);
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var targets = buildMarkedChunksFromDetected(world, detected.result().chunks());
            if (targets.isEmpty()) {
                sender.sendMessage(Component.text(messages.text(
                    "biome-no-regenerable-chunks", detected.biomeId())).color(AdminTuiBuilder.SEVERE));
                return;
            }
            var selection = new BiomeSelection(
                detected.biomeId(), List.copyOf(targets), detected.result().truncated());
            resetCommands.resetDetectedBiome(sender, targets, false, confirmationKey,
                detected.biomeId(), selection);
        });
    }

    /** Shared by /cr mark biomeradius and /cr mark here biome; xOpt/zOpt null means "use sender's location". */
    private void doBiomeRadiusMark(Commander sender, World world, String biomeIdsRaw, int radius, Integer xOpt, Integer zOpt) {
        String worldName = world.getName();
        var cfg = plugin.getPluginConfig();
        if (radius > cfg.biome.biomeradiusMaxRadiusChunks) {
            sender.sendMessage(messages.scan.radiusTooLarge.withPlaceholders(
                Components.placeholder("radius", String.valueOf(radius)),
                Components.placeholder("max", String.valueOf(cfg.biome.biomeradiusMaxRadiusChunks))));
            return;
        }

        String resolvedBiomeIds = resolveBiomeIdsArg(sender, world, biomeIdsRaw);
        if (resolvedBiomeIds == null) {
            sender.sendMessage(Component.text(messages.text("here-biome-requires-player", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }

        var targets = parseBiomeIds(world, resolvedBiomeIds);
        if (!targets.invalid().isEmpty()) {
            sender.sendMessage(messages.scan.unknownBiome.withPlaceholders(
                Components.placeholder("ids", String.join(", ", targets.invalid()))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            sender.sendMessage(messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!cfg.scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            sender.sendMessage(messages.scan.regenRunning.asComponent());
            return;
        }

        int blockX, blockZ;
        if (xOpt != null && zOpt != null) {
            blockX = xOpt;
            blockZ = zOpt;
        } else if (sender instanceof PlayerCommander playerCommander) {
            var loc = playerCommander.player().getLocation();
            blockX = loc.getBlockX();
            blockZ = loc.getBlockZ();
        } else {
            var spawn = world.getSpawnLocation();
            blockX = spawn.getBlockX();
            blockZ = spawn.getBlockZ();
        }

        var area = new DiskChunkScanner.ScanArea(blockX >> 4, blockZ >> 4, radius);
        var filter = new DiskChunkScanner.BiomeFilter(targets.resolved(), cfg.biome.matchModeEnum());
        startScan(sender, world, area, filter);
    }

    public BlockingSuggestionProvider.Strings<Commander> biomeIdSuggestions() {
        return (ctx, input) -> {
            var worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) return List.of();
            World world = worlds.get(0);
            String raw = input.peekString();
            int lastComma = raw.lastIndexOf(',');
            String prefix = lastComma >= 0 ? raw.substring(0, lastComma + 1) : "";
            String partial = lastComma >= 0 ? raw.substring(lastComma + 1) : raw;
            var suggestions = new ArrayList<String>();
            // "here" only makes sense as the sole value (resolveBiomeIdsArg), not combined via comma.
            if (lastComma < 0 && "here".startsWith(partial.toLowerCase(java.util.Locale.ROOT))) {
                suggestions.add("here");
            }
            chunkScanService.biomeIds(world).stream()
                .filter(id -> id.startsWith(partial))
                .forEach(id -> suggestions.add(prefix + id));
            return suggestions;
        };
    }

    public void deleteHereBiome(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        if (!worldAccessPolicy.isAllowed(world.getName(), WorldAccessPolicy.Scope.REGEN)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        String confirmationKey = "delete-here-biome:" + world.getName();
        if (confirmed) {
            var cached = confirmationManager.peekPayload(sender.commanderId(), confirmationKey);
            if (cached.isEmpty() || !(cached.get() instanceof BiomeSelection selection)
                    || !confirmationManager.confirm(sender.commanderId(), confirmationKey)) {
                sender.sendMessage(messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueChunks(sender, selection.chunks());
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            List<MarkedChunk> chunks = buildMarkedChunksFromDetected(world, detected.result().chunks());
            if (chunks.isEmpty()) {
                sender.sendMessage(Component.text(messages.text("biome-no-deletable-chunks"))
                    .color(AdminTuiBuilder.SECONDARY));
                return;
            }
            var selection = new BiomeSelection(
                detected.biomeId(), List.copyOf(chunks), detected.result().truncated());
            confirmationManager.request(sender.commanderId(), confirmationKey,
                plugin.getPluginConfig().safety.confirmTimeoutSeconds, selection);
            String truncated = selection.truncated() ? messages.text("biome-delete-truncated") : "";
            sender.sendMessage(Component.text(messages.text(
                "biome-delete-confirm", selection.biomeId(), chunks.size(), truncated,
                plugin.getPluginConfig().safety.confirmTimeoutSeconds))
                .color(TextColor.color(0xFFAA00)));
        });
    }


}

package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
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
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Handles persisted-chunk scans and biome-based marking/regeneration. */
public final class ScanCommands {

    private record FullMarkSelection(List<UUID> worlds) {

        private FullMarkSelection {
            worlds = List.copyOf(worlds);
        }
    }

    private record BiomeFullMarkSelection(DiskChunkScanner.BiomeFilter filter) {

        private BiomeFullMarkSelection {
            filter = new DiskChunkScanner.BiomeFilter(Set.copyOf(filter.targets()), filter.matchMode());
        }
    }

    private record PlayerBiomeOrigin(String world, int chunkX, int chunkZ) {}

    private final ChunkRevivePlugin                              plugin;
    private final WorldAccessPolicy                              worldAccessPolicy;
    private final ChunkScanService                               chunkScanService;
    private final BiConsumer<Commander, Collection<MarkedChunk>> bulkReset;
    private final ConfirmationManager                            confirmationManager;
    private final DeletionService                                deletionService;
    private Messages                                             messages;

    public ScanCommands(ChunkRevivePlugin plugin, WorldAccessPolicy worldAccessPolicy,
        ChunkScanService chunkScanService, BiConsumer<Commander, Collection<MarkedChunk>> bulkReset,
        ConfirmationManager confirmationManager, DeletionService deletionService, Messages messages) {
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
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> fullMark(sender, worldName, confirmed))) {
            return;
        }
        fullMark(sender, worldName, confirmed);
    }

    private void fullMark(Commander sender, String worldName, boolean confirmed) {
        String confirmationKey = "fullmark:" + worldName.toLowerCase(Locale.ROOT);
        var confirmedSelection = confirmed ? confirmationManager.consumePayload(sender.commanderId(), confirmationKey,
            FullMarkSelection.class) : Optional.<FullMarkSelection>empty();
        if (confirmed && confirmedSelection.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
            return;
        }
        List<World> worlds;
        if (confirmed) {
            worlds = confirmedSelection.get()
                .worlds()
                .stream()
                .map(Bukkit::getWorld)
                .filter(Objects::nonNull)
                .filter(world -> worldAccessPolicy.isAllowed(world.getName(), WorldAccessPolicy.Scope.BULK_MARK))
                .toList();
        } else if ("all".equalsIgnoreCase(worldName)) {
            worlds = Bukkit.getWorlds()
                .stream()
                .filter(world -> worldAccessPolicy.isAllowed(world.getName(), WorldAccessPolicy.Scope.BULK_MARK))
                .toList();
        } else {
            worlds = Optional.ofNullable(Bukkit.getWorld(worldName)).stream().toList();
        }
        if (worlds.isEmpty()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        if (!"all".equalsIgnoreCase(worldName) && !worldAccessPolicy.isAllowed(worldName,
            WorldAccessPolicy.Scope.BULK_MARK)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }
        if (!"all".equalsIgnoreCase(worldName) && chunkScanService.isRunning(worldName)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!plugin.getPluginConfig().scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.regenRunning.asComponent());
            return;
        }

        if (confirmed) {
            for (World world : worlds) {
                if (chunkScanService.isRunning(world.getName())) {
                    CommandMessageRouter.send(plugin, sender, Component.text(messages.text("scan-already-running-world",
                        world.getName())).color(AdminTuiBuilder.SECONDARY));
                    continue;
                }
                startScan(sender, world, null, null);
            }
            return;
        }

        var cfg = plugin.getPluginConfig();
        List<CompletableFuture<Integer>> regionCounts = worlds.stream()
            .map(world -> chunkScanService.countRegionsAsync(world, null))
            .toList();
        CompletableFuture.allOf(regionCounts.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            Runnable completion = () -> {
                if (failure != null) {
                    Throwable cause = failure;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    CommandMessageRouter.send(plugin, sender, messages.scan.scanFailed.withPlaceholders(
                        Components.placeholder("reason", cause.getMessage() == null ? cause.getClass().getSimpleName()
                            : cause.getMessage())));
                    return;
                }
                int regionCount = regionCounts.stream().mapToInt(CompletableFuture::join).sum();
                confirmationManager.request(sender.commanderId(), confirmationKey, cfg.safety.confirmTimeoutSeconds,
                    new FullMarkSelection(worlds.stream().map(World::getUID).toList()));
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmRequired.withPlaceholders(
                    Components.placeholder("world", worldName), Components.placeholder("region_count", String.valueOf(
                        regionCount)), Components.placeholder("timeout", String.valueOf(
                            cfg.safety.confirmTimeoutSeconds)), Components.placeholder("command", "/cr mark fullmark "
                                + worldName)));
            };
            if (!CommandMessageRouter.dispatchGlobal(plugin, completion)) {
                completion.run();
            }
        });
    }

    public void radiusMark(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        int radius = ctx.get("radius");
        Integer x = ctx.<Integer>optional("x").orElse(null);
        Integer z = ctx.<Integer>optional("z").orElse(null);
        DiskChunkScanner.ScanArea area = null;
        if (x != null && z != null) {
            area = new DiskChunkScanner.ScanArea(x >> 4, z >> 4, radius);
        } else if (sender instanceof PlayerCommander playerSender) {
            var location = playerSender.player().getLocation();
            area = new DiskChunkScanner.ScanArea(location.getBlockX() >> 4, location.getBlockZ() >> 4, radius);
        }
        var scanArea = area;
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> radiusMark(sender, worldName, radius, scanArea))) {
            return;
        }
        radiusMark(sender, worldName, radius, scanArea);
    }

    private void radiusMark(Commander sender, String worldName, int radius, DiskChunkScanner.ScanArea area) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        var cfg = plugin.getPluginConfig();
        if (radius > cfg.scan.radiusmarkMaxRadiusChunks) {
            CommandMessageRouter.send(plugin, sender, messages.scan.radiusTooLarge.withPlaceholders(
                Components.placeholder("radius", String.valueOf(radius)), Components.placeholder("max", String.valueOf(
                    cfg.scan.radiusmarkMaxRadiusChunks))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!cfg.scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.regenRunning.asComponent());
            return;
        }

        startScan(sender, world, area == null ? scanArea(sender, world, radius, null, null) : area, null);
    }

    private void startScan(Commander sender, World world, DiskChunkScanner.ScanArea area,
        DiskChunkScanner.BiomeFilter biomeFilter) {
        CommandMessageRouter.send(plugin, sender, messages.scan.scanStarted.withPlaceholders(Components.placeholder(
            "world", world.getName())));
        chunkScanService.scan(world, area, biomeFilter).whenComplete((result, ex) -> Scheduler.runTask(plugin, () -> {
            if (ex != null) {
                CommandMessageRouter.send(plugin, sender, messages.scan.scanFailed.withPlaceholders(
                    Components.placeholder("reason", ex.getMessage() != null ? ex.getMessage()
                        : ex.getClass().getSimpleName())));
                return;
            }
            if (result.cancelled()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.scanCancelled.asComponent());
                return;
            }
            CommandMessageRouter.send(plugin, sender, messages.scan.scanComplete.withPlaceholders(
                Components.placeholder("found", String.valueOf(result.chunksFound())), Components.placeholder("marked",
                    String.valueOf(result.chunksMarked())), Components.placeholder("skipped_existing", String.valueOf(
                        result.chunksSkippedExisting())), Components.placeholder("skipped_claimed", String.valueOf(
                            result.chunksSkippedClaimed())), Components.placeholder("skipped_biome_mismatch",
                                String.valueOf(result.chunksSkippedBiomeMismatch()))));
        }));
    }

    // ── biomeradius / biomefull ─────────────────────────────────────────────

    /**
     * Resolves the special "here" keyword to the real biome id at the sender's current location
     * (so a player can mark "whatever biome I'm standing in" without typing its id); any other
     * input is returned unchanged for biome ID parsing. Returns null if "here" was
     * used but the sender isn't a player standing in the target world.
     */
    private String resolveBiomeIdsArg(Commander sender, World world, String raw) {
        return resolveBiomeIdsArg(world, raw, playerBiomeOrigin(sender));
    }

    private String resolveBiomeIdsArg(World world, String raw, PlayerBiomeOrigin origin) {
        if (!raw.trim().equalsIgnoreCase("here")) {
            return raw;
        }
        if (origin == null || !origin.world().equals(world.getName())) {
            return null;
        }
        // Sampled at the chunk's center (BiomeMatcher.centerBiome), not the player's exact block
        // position: biome resolution is quart (4-block) granularity, finer than a chunk, so the
        // corner of a chunk a player happens to stand on can genuinely differ from that chunk's
        // center — sampling the exact position here would disagree with how BiomeMatcher.matches()
        // tests that same chunk under MatchMode.CENTER, making the player's own starting chunk
        // fail to match itself.
        return chunkScanService.centerBiome(world, origin.chunkX(), origin.chunkZ());
    }

    private PlayerBiomeOrigin playerBiomeOrigin(Commander sender) {
        if (!(sender instanceof PlayerCommander playerSender)) {
            return null;
        }
        var player = playerSender.player();
        var location = player.getLocation();
        return new PlayerBiomeOrigin(player.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public void biomeFullMark(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        String biomeIdsRaw = ctx.get("biomeIds");
        var origin = playerBiomeOrigin(sender);
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> biomeFullMark(sender, worldName, biomeIdsRaw, origin,
            confirmed))) {
            return;
        }
        biomeFullMark(sender, worldName, biomeIdsRaw, origin, confirmed);
    }

    private void biomeFullMark(Commander sender, String worldName, String biomeIdsRaw, PlayerBiomeOrigin origin,
        boolean confirmed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        String resolvedBiomeIds = resolveBiomeIdsArg(world, biomeIdsRaw, origin);
        if (resolvedBiomeIds == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("here-biome-requires-player",
                worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }
        String resolvedIds = resolvedBiomeIds;

        var targets = chunkScanService.parseBiomeIds(world, resolvedIds);
        if (!targets.invalid().isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.unknownBiome.withPlaceholders(
                Components.placeholder("ids", String.join(", ", targets.invalid()))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!plugin.getPluginConfig().scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.regenRunning.asComponent());
            return;
        }

        var filter = new DiskChunkScanner.BiomeFilter(targets.resolved(),
            plugin.getPluginConfig().biome.matchModeEnum());
        String confirmationKey = "biome-fullmark:" + world.getUID() + ":" + String.join(",",
            targets.resolved().stream().sorted().toList());
        if (confirmed) {
            var selection = confirmationManager.consumePayload(sender.commanderId(), confirmationKey,
                BiomeFullMarkSelection.class);
            if (selection.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            startScan(sender, world, null, selection.get().filter());
            return;
        }

        var cfg = plugin.getPluginConfig();
        chunkScanService.countRegionsAsync(world, null).whenComplete((regionCount, failure) -> {
            Runnable completion = () -> {
                if (failure != null) {
                    Throwable cause = failure;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    CommandMessageRouter.send(plugin, sender, messages.scan.scanFailed.withPlaceholders(
                        Components.placeholder("reason", cause.getMessage() == null ? cause.getClass().getSimpleName()
                            : cause.getMessage())));
                    return;
                }
                confirmationManager.request(sender.commanderId(), confirmationKey, cfg.safety.confirmTimeoutSeconds,
                    new BiomeFullMarkSelection(filter));
                CommandMessageRouter.send(plugin, sender, messages.scan.biomeConfirmRequired.withPlaceholders(
                    Components.placeholder("world", worldName), Components.placeholder("biomes", resolvedIds),
                    Components.placeholder("region_count", String.valueOf(regionCount)), Components.placeholder(
                        "timeout", String.valueOf(cfg.safety.confirmTimeoutSeconds)), Components.placeholder("command",
                            "/cr mark biomefull " + worldName + " " + resolvedIds)));
            };
            if (!CommandMessageRouter.dispatchGlobal(plugin, completion)) {
                completion.run();
            }
        });
    }

    public void biomeRadiusMark(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        String biomeIdsRaw = ctx.get("biomeIds");
        int radius = ctx.get("radius");
        Integer x = ctx.<Integer>optional("x").orElse(null);
        Integer z = ctx.<Integer>optional("z").orElse(null);
        DiskChunkScanner.ScanArea area = null;
        if (x != null && z != null) {
            area = new DiskChunkScanner.ScanArea(x >> 4, z >> 4, radius);
        } else if (sender instanceof PlayerCommander playerSender) {
            var location = playerSender.player().getLocation();
            area = new DiskChunkScanner.ScanArea(location.getBlockX() >> 4, location.getBlockZ() >> 4, radius);
        }
        var origin = playerBiomeOrigin(sender);
        var scanArea = area;
        if (CommandMessageRouter.dispatchGlobal(plugin, () -> biomeRadiusMark(sender, worldName, biomeIdsRaw, radius,
            scanArea, origin))) {
            return;
        }
        biomeRadiusMark(sender, worldName, biomeIdsRaw, radius, scanArea, origin);
    }

    private void biomeRadiusMark(Commander sender, String worldName, String biomeIdsRaw, int radius,
        DiskChunkScanner.ScanArea area, PlayerBiomeOrigin origin) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("world-not-found", worldName)).color(
                AdminTuiBuilder.SEVERE));
            return;
        }
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        doBiomeRadiusMark(sender, world, biomeIdsRaw, radius, area, origin);
    }

    /** Players-only convenience: marks the whole contiguous biome patch the player is currently standing in. */
    public void markHereBiome(CommandContext<PlayerCommander> ctx) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        String worldName = world.getName();
        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.BULK_MARK)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var cfg = plugin.getPluginConfig();
            var toMark = chunkScanService.buildBiomeCandidates(world, detected.result().chunks());
            if (toMark.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-no-markable-chunks",
                    detected.biomeId())).color(AdminTuiBuilder.SEVERE));
                return;
            }
            chunkScanService.markCandidates(toMark).whenComplete((outcome, failure) -> Scheduler.runTask(plugin, () -> {
                if (!sender.player().isOnline()) {
                    return;
                }
                if (failure != null) {
                    plugin.getLogger().warning("Biome protection check failed: " + failure.getMessage());
                    CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-no-markable-chunks",
                        detected.biomeId())).color(AdminTuiBuilder.SEVERE));
                    return;
                }
                String truncated = detected.result().truncated() ? messages.text("biome-mark-truncated",
                    cfg.biome.regen.floodFillMaxChunks) : "";
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-marked",
                    detected.biomeId(), outcome.added().size(), outcome.eligible().size(), truncated)).color(
                        AdminTuiBuilder.NORMAL));
            }, sender.player()));
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
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("player-only")).color(
                AdminTuiBuilder.SEVERE));
            return CompletableFuture.completedFuture(null);
        }
        String resolvedBiomeId = resolveBiomeIdsArg(sender, world, "here");
        if (resolvedBiomeId == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-undetermined")).color(
                AdminTuiBuilder.SEVERE));
            return CompletableFuture.completedFuture(null);
        }
        var targets = chunkScanService.parseBiomeIds(world, resolvedBiomeId);
        if (!targets.invalid().isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.unknownBiome.withPlaceholders(
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
            if (detected == null) {
                return;
            }
            Scheduler.runTask(plugin, () -> {
                if (sender.player().isOnline()) {
                    action.accept(detected);
                }
            }, sender.player());
        });
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
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }
        if (chunkScanService.isRegenerationRunning()) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("regen-busy")).color(
                AdminTuiBuilder.SEVERE));
            return;
        }

        String confirmationKey = "regen-here-biome:" + worldName;
        if (confirmed) {
            BiomeSelection selection = confirmationManager.consumePayload(sender.commanderId(), confirmationKey,
                BiomeSelection.class).orElse(null);
            if (selection == null) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            executeBiomeRegeneration(sender, selection);
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var cfg = plugin.getPluginConfig();
            var toMark = chunkScanService.buildBiomeCandidates(world, detected.result().chunks());
            if (toMark.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-no-regenerable-chunks",
                    detected.biomeId())).color(AdminTuiBuilder.SEVERE));
                return;
            }

            int threshold = cfg.safety.bulkRegenConfirmThresholdChunks;
            var selection = new BiomeSelection(detected.biomeId(), toMark, detected.result().truncated());
            if (toMark.size() > threshold) {
                confirmationManager.request(sender.commanderId(), confirmationKey, cfg.safety.confirmTimeoutSeconds,
                    selection);
                String truncated = detected.result().truncated() ? messages.text("biome-regen-truncated",
                    cfg.biome.regen.floodFillMaxChunks) : "";
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-regen-confirm",
                    detected.biomeId(), toMark.size(), truncated, cfg.safety.confirmTimeoutSeconds)).color(
                        TextColor.color(0xFFAA00)));
                return;
            }
            executeBiomeRegeneration(sender, selection);
        });
    }

    private void executeBiomeRegeneration(PlayerCommander sender, BiomeSelection selection) {
        chunkScanService.markDirect(selection.chunks()).whenComplete((outcome, failure) -> Scheduler.runTask(plugin,
            () -> {
                if (!sender.player().isOnline()) {
                    return;
                }
                if (failure != null || outcome.eligible().isEmpty()) {
                    if (failure != null) {
                        plugin.getLogger().warning("Biome protection check failed: " + failure.getMessage());
                    }
                    CommandMessageRouter.send(plugin, sender, Component.text(messages.text(
                        "biome-no-regenerable-chunks", selection.biomeId())).color(AdminTuiBuilder.SEVERE));
                    return;
                }
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-regen-start",
                    selection.biomeId(), outcome.eligible().size())).color(AdminTuiBuilder.NORMAL));
                bulkReset.accept(sender, outcome.eligible());
            }, sender.player()));
    }

    public void resetHereBiome(CommandContext<PlayerCommander> ctx, boolean confirmed, ResetCommands resetCommands) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        String worldName = world.getName();

        if (!worldAccessPolicy.isAllowed(worldName, WorldAccessPolicy.Scope.REGEN)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", worldName)));
            return;
        }

        String confirmationKey = "reset-here-biome:" + worldName;
        if (confirmed) {
            ResetCommands.BulkResetSelection selection = confirmationManager.peekPayload(sender.commanderId(),
                confirmationKey, ResetCommands.BulkResetSelection.class).orElse(null);
            if (selection == null || selection.biomeSelection() == null) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            resetCommands.resetDetectedBiome(sender, selection.biomeSelection(), true, confirmationKey);
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            var targets = chunkScanService.buildBiomeCandidates(world, detected.result().chunks());
            if (targets.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-no-regenerable-chunks",
                    detected.biomeId())).color(AdminTuiBuilder.SEVERE));
                return;
            }
            var selection = new BiomeSelection(detected.biomeId(), targets, detected.result().truncated());
            resetCommands.resetDetectedBiome(sender, selection, false, confirmationKey);
        });
    }

    private void doBiomeRadiusMark(Commander sender, World world, String biomeIdsRaw, int radius,
        DiskChunkScanner.ScanArea area, PlayerBiomeOrigin origin) {
        String worldName = world.getName();
        var cfg = plugin.getPluginConfig();
        if (radius > cfg.biome.biomeradiusMaxRadiusChunks) {
            CommandMessageRouter.send(plugin, sender, messages.scan.radiusTooLarge.withPlaceholders(
                Components.placeholder("radius", String.valueOf(radius)), Components.placeholder("max", String.valueOf(
                    cfg.biome.biomeradiusMaxRadiusChunks))));
            return;
        }

        String resolvedBiomeIds = resolveBiomeIdsArg(world, biomeIdsRaw, origin);
        if (resolvedBiomeIds == null) {
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("here-biome-requires-player",
                worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }

        var targets = chunkScanService.parseBiomeIds(world, resolvedBiomeIds);
        if (!targets.invalid().isEmpty()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.unknownBiome.withPlaceholders(
                Components.placeholder("ids", String.join(", ", targets.invalid()))));
            return;
        }
        if (chunkScanService.isRunning(worldName)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.scanAlreadyRunning.asComponent());
            return;
        }
        if (!cfg.scan.allowConcurrentWithRegen && chunkScanService.isRegenerationRunning()) {
            CommandMessageRouter.send(plugin, sender, messages.scan.regenRunning.asComponent());
            return;
        }

        var filter = new DiskChunkScanner.BiomeFilter(targets.resolved(), cfg.biome.matchModeEnum());
        startScan(sender, world, area == null ? scanArea(sender, world, radius, null, null) : area, filter);
    }

    private DiskChunkScanner.ScanArea scanArea(Commander sender, World world, int radius, Integer x, Integer z) {
        if (x != null && z != null) {
            return new DiskChunkScanner.ScanArea(x >> 4, z >> 4, radius);
        }
        var location = sender instanceof PlayerCommander player ? player.player().getLocation()
            : world.getSpawnLocation();
        return new DiskChunkScanner.ScanArea(location.getBlockX() >> 4, location.getBlockZ() >> 4, radius);
    }

    public SuggestionProvider<Commander> biomeIdSuggestions() {
        return (ctx, input) -> {
            String raw = input.peekString();
            int lastComma = raw.lastIndexOf(',');
            String prefix = lastComma >= 0 ? raw.substring(0, lastComma + 1) : "";
            String partial = lastComma >= 0 ? raw.substring(lastComma + 1) : raw;
            var suggestions = new CompletableFuture<Iterable<? extends Suggestion>>();
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    List<World> worlds = Bukkit.getWorlds();
                    if (worlds.isEmpty()) {
                        suggestions.complete(List.of());
                        return;
                    }
                    var values = new ArrayList<String>();
                    if (lastComma < 0 && "here".startsWith(partial.toLowerCase(Locale.ROOT))) {
                        values.add("here");
                    }
                    chunkScanService.biomeIds(worlds.getFirst())
                        .stream()
                        .filter(id -> id.startsWith(partial))
                        .map(id -> prefix + id)
                        .forEach(values::add);
                    suggestions.complete(values.stream().map(Suggestion::suggestion).toList());
                } catch (Throwable failure) {
                    suggestions.completeExceptionally(failure);
                }
            });
            return suggestions;
        };
    }

    public void deleteHereBiome(CommandContext<PlayerCommander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        World world = sender.player().getWorld();
        if (!worldAccessPolicy.isAllowed(world.getName(), WorldAccessPolicy.Scope.REGEN)) {
            CommandMessageRouter.send(plugin, sender, messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world.getName())));
            return;
        }
        String confirmationKey = "delete-here-biome:" + world.getName();
        if (confirmed) {
            BiomeSelection selection = confirmationManager.consumePayload(sender.commanderId(), confirmationKey,
                BiomeSelection.class).orElse(null);
            if (selection == null) {
                CommandMessageRouter.send(plugin, sender, messages.scan.confirmExpired.asComponent());
                return;
            }
            deletionService.queueChunks(sender, selection.chunks());
            return;
        }

        onHereBiomeDetected(sender, world, detected -> {
            List<MarkedChunk> chunks = chunkScanService.buildBiomeCandidates(world, detected.result().chunks());
            if (chunks.isEmpty()) {
                CommandMessageRouter.send(plugin, sender, Component.text(messages.text(
                    "biome-no-deletable-chunks")).color(AdminTuiBuilder.SECONDARY));
                return;
            }
            var selection = new BiomeSelection(detected.biomeId(), chunks, detected.result().truncated());
            confirmationManager.request(sender.commanderId(), confirmationKey,
                plugin.getPluginConfig().safety.confirmTimeoutSeconds, selection);
            String truncated = selection.truncated() ? messages.text("biome-delete-truncated") : "";
            CommandMessageRouter.send(plugin, sender, Component.text(messages.text("biome-delete-confirm",
                selection.biomeId(), chunks.size(), truncated,
                plugin.getPluginConfig().safety.confirmTimeoutSeconds)).color(TextColor.color(0xFFAA00)));
        });
    }

}

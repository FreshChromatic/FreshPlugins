package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.FollowMode;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.incendo.cloud.context.CommandContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Handles direct chunk mark/unmark operations and follow modes. */
public final class MarkCommands {
    private static final int PAGE_SIZE = 8;
    private final ChunkRevivePlugin plugin;
    private final MarkService markService;
    private Messages messages;

    public MarkCommands(
            ChunkRevivePlugin plugin,
            MarkService markService,
            Messages messages) {
        this.plugin = plugin;
        this.markService = markService;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void mark(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        int cx = chunk.getX(), cz = chunk.getZ();
        var result = markService.mark(world, cx, cz, player.getUniqueId());
        var mc = new MarkedChunk(world, cx, cz, player.getUniqueId(), 0);
        player.sendMessage(switch (result) {
            case SUCCESS -> messages.mark.success.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case ALREADY_MARKED -> messages.mark.already.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case CLAIM_BLOCKED -> messages.mark.residenceBlocked.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case WORLD_NOT_ALLOWED -> messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world));
        });
    }

    public void markCoordinates(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        String world = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        UUID actor = sender instanceof PlayerCommander pc ? pc.player().getUniqueId() : UUID.randomUUID();
        var result = markService.mark(world, cx, cz, actor);
        var mc = new MarkedChunk(world, cx, cz, actor, 0);
        sender.sendMessage(switch (result) {
            case SUCCESS -> messages.mark.success.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case ALREADY_MARKED -> messages.mark.already.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case CLAIM_BLOCKED -> messages.mark.residenceBlocked.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()));
            case WORLD_NOT_ALLOWED -> messages.scan.worldNotAllowed.withPlaceholders(Components.placeholder("world", world));
        });
    }

    public void markFollow(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        boolean on = markService.toggleFollowMode(player.getUniqueId(), FollowMode.MARK);
        player.sendMessage(on ? messages.mark.followOn.asComponent() : messages.mark.followOff.asComponent());
    }

    public void unmark(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        var chunk = player.getLocation().getChunk();
        String world = player.getWorld().getName();
        int cx = chunk.getX(), cz = chunk.getZ();
        doUnmark(ctx.sender(), world, cx, cz, player.getUniqueId());
    }

    public void unmarkFollow(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        boolean on = markService.toggleFollowMode(player.getUniqueId(), FollowMode.UNMARK);
        player.sendMessage(on ? messages.unmark.followOn.asComponent() : messages.unmark.followOff.asComponent());
    }

    public void unmarkCoordinates(CommandContext<Commander> ctx) {
        String world = ctx.get("world");
        int cx = ctx.get("cx");
        int cz = ctx.get("cz");
        doUnmark(ctx.sender(), world, cx, cz, null);
    }

    private void doUnmark(Commander sender, String world, int cx, int cz, UUID actor) {
        boolean removed = markService.unmark(world, cx, cz, actor);
        var mc = new MarkedChunk(world, cx, cz, UUID.randomUUID(), 0);
        sender.sendMessage(removed
            ? messages.unmark.success.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay()))
            : messages.unmark.notMarked.withPlaceholders(Components.placeholder("cx_cz", mc.coordDisplay())));
    }


    public void resetMarks(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        String worldName = ctx.get("world");
        if (Bukkit.getWorld(worldName) == null) {
            sender.sendMessage(Component.text(messages.text("world-not-found", worldName)).color(AdminTuiBuilder.SEVERE));
            return;
        }

        // resetmark deliberately ignores the worlds whitelist/blacklist (§5.2): clearing stale marks
        // must stay possible even for a world that has since been disallowed.
        String key = "resetmark:" + worldName;
        if (confirmed) {
            var result = markService.resetWorld(worldName);
            sender.sendMessage(messages.scan.resetComplete.withPlaceholders(
                Components.placeholder("world", worldName),
                Components.placeholder("count", String.valueOf(result.chunkCount())),
                Components.placeholder("group_count", String.valueOf(result.groupCount()))));
            return;
        }

        var cfg = plugin.getPluginConfig();
        sender.sendMessage(messages.scan.resetConfirmRequired.withPlaceholders(
            Components.placeholder("world", worldName),
            Components.placeholder("timeout", String.valueOf(cfg.safety.confirmTimeoutSeconds))));
    }

    public void list(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        int page = ctx.getOrDefault("page", 1);
        // Structure-grouped chunks are managed via /cr struct list instead.
        var all = markService.independentMarksNewestFirst();

        if (all.isEmpty()) {
            sender.sendMessage(AdminTuiBuilder.line(
                messages.list.empty.asComponent()));
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        page = Math.clamp(page, 1, totalPages);
        var pageItems = all.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, all.size()));

        sender.sendMessage(AdminTuiBuilder.header(messages.list.title.asComponent()));
        sender.sendMessage(AdminTuiBuilder.divider());

        for (var chunk : pageItems) {
            var entryText = messages.list.entry.withPlaceholders(
                Components.placeholder("world", chunk.world()),
                Components.placeholder("cx_cz", chunk.coordDisplay()),
                Components.placeholder("marked_by", resolveDisplayName(chunk.markedBy())),
                Components.placeholder("marked_at", formatRelative(chunk.markedAt())));

            var unmarkCmd = "/cr unmark unmark %s %d %d".formatted(chunk.world(), chunk.cx(), chunk.cz());
            var regenCmd  = "/cr regen regen %s %d %d".formatted(chunk.world(), chunk.cx(), chunk.cz());

            var unmarkBtn = AdminTuiBuilder.severeButton(
                messages.list.btnUnmark.asComponent(),
                ClickEvent.runCommand(unmarkCmd),
                Component.text(messages.text("list-unmark-hover")).color(AdminTuiBuilder.SECONDARY));

            var regenBtn = AdminTuiBuilder.actionButton(
                messages.list.btnRegen.asComponent(),
                ClickEvent.runCommand(regenCmd),
                Component.text(messages.text("list-regen-hover")).color(AdminTuiBuilder.SECONDARY));

            sender.sendMessage(
                Component.text()
                    .append(unmarkBtn)
                    .append(Component.text(" "))
                    .append(regenBtn)
                    .append(Component.text(" "))
                    .append(AdminTuiBuilder.line(entryText))
                    .build());
        }

        sender.sendMessage(AdminTuiBuilder.divider());

        var prevBtn = page > 1
            ? AdminTuiBuilder.normalButton(
                messages.list.btnPrev.asComponent(),
                ClickEvent.runCommand("/cr mark list " + (page - 1)),
                Component.empty())
            : AdminTuiBuilder.disabledButton(messages.list.btnPrev.asComponent());

        var nextBtn = page < totalPages
            ? AdminTuiBuilder.normalButton(
                messages.list.btnNext.asComponent(),
                ClickEvent.runCommand("/cr mark list " + (page + 1)),
                Component.empty())
            : AdminTuiBuilder.disabledButton(messages.list.btnNext.asComponent());

        var pageCounter = messages.list.pageInfo.withPlaceholders(
            Components.placeholder("page", String.valueOf(page)),
            Components.placeholder("total", String.valueOf(totalPages)));

        sender.sendMessage(AdminTuiBuilder.actionBar(
            Component.text()
                .append(prevBtn)
                .append(Component.text("  ").color(AdminTuiBuilder.SECONDARY))
                .append(pageCounter)
                .append(Component.text("  ").color(AdminTuiBuilder.SECONDARY))
                .append(nextBtn)
                .build()));
    }

    private static String resolveDisplayName(UUID uuid) {
        var player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString().substring(0, 8);
    }

    private String formatRelative(long epochMillis) {
        long seconds = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now()).getSeconds();
        if (seconds < 60) return messages.text("relative-seconds", seconds);
        if (seconds < 3600) return messages.text("relative-minutes", seconds / 60);
        if (seconds < 86400) return messages.text("relative-hours", seconds / 3600);
        return messages.text("relative-days", seconds / 86400);
    }
}

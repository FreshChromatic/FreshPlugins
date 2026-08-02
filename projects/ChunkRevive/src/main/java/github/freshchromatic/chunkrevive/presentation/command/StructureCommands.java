package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import github.freshchromatic.chunkrevive.feature.structure.StructureGroup;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.context.CommandContext;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Handles structure inspection, protection state and forced regeneration. */
public final class StructureCommands {
    private static final int PAGE_SIZE = 8;

    private final StructureService structureService;
    private final BiConsumer<Commander, Collection<MarkedChunk>> bulkReset;
    private Messages messages;

    public StructureCommands(
            StructureService structureService,
            BiConsumer<Commander, Collection<MarkedChunk>> bulkReset,
            Messages messages) {
        this.structureService = structureService;
        this.bulkReset = bulkReset;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    // ── Structure handlers ───────────────────────────────────────────────────

    public void markHere(CommandContext<PlayerCommander> ctx) {
        var sender = ctx.sender();
        var player = sender.player();
        String world = player.getWorld().getName();
        if (!structureService.canAutoDetect(world)) {
            sender.sendMessage(messages.scan.worldNotAllowed.withPlaceholders(
                Components.placeholder("world", world)));
            return;
        }

        var location = player.getLocation();
        var detections = structureService.detectAndRegister(
            player.getWorld(), location.getBlockX(), location.getBlockZ(), player.getUniqueId());
        if (detections.isEmpty()) {
            sender.sendMessage(messages.structure.checkOutside.asComponent());
            return;
        }

        for (var detection : detections) {
            sender.sendMessage(messages.structure.detected.withPlaceholders(
                Components.placeholder("structure_name", StructureRegistry.displayName(detection.structureId())),
                Components.placeholder("count", String.valueOf(detection.newlyMarked()))));
        }
    }

    public void check(CommandContext<PlayerCommander> ctx) {
        var player = ctx.sender().player();
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;
        String world = player.getWorld().getName();

        Optional<StructureGroup> group = structureService.findAt(world, cx, cz);
        if (group.isEmpty()) {
            ctx.sender().sendMessage(messages.structure.checkOutside.asComponent());
            return;
        }

        var g = group.get();
        ctx.sender().sendMessage(messages.structure.checkInside.withPlaceholders(
            Components.placeholder("structure_name", StructureRegistry.displayName(g.structureId())),
            Components.placeholder("cx_cz", "CX %d / CZ %d".formatted(cx, cz)),
            Components.placeholder("range", g.rangeDisplay())));

        long remainingMs = Math.max(0, g.nextRefreshAt() - System.currentTimeMillis());
        String status = g.blocked() ? messages.text("structure-protected") : messages.text("structure-scheduled");
        ctx.sender().sendMessage(messages.structure.checkRefresh.withPlaceholders(
            Components.placeholder("days", String.valueOf(remainingMs / 86_400_000L)),
            Components.placeholder("hours", String.valueOf((remainingMs / 3_600_000L) % 24)),
            Components.placeholder("status", status)));
    }

    public void list(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        int page = ctx.getOrDefault("page", 1);

        var all = structureService.groupsByNextRefresh();
        if (all.isEmpty()) {
            sender.sendMessage(AdminTuiBuilder.line(messages.structure.list.empty.asComponent()));
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        page = Math.clamp(page, 1, totalPages);
        var pageItems = all.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, all.size()));

        sender.sendMessage(AdminTuiBuilder.header(messages.structure.list.title.asComponent()));
        sender.sendMessage(AdminTuiBuilder.divider());

        for (var group : pageItems) {
            long remainingMs = Math.max(0, group.nextRefreshAt() - System.currentTimeMillis());
            String status = group.blocked() ? messages.text("structure-protected")
                : messages.text("structure-refresh-in-days", remainingMs / 86_400_000L);
            sender.sendMessage(AdminTuiBuilder.line(messages.structure.list.entry.withPlaceholders(
                Components.placeholder("structure_name", StructureRegistry.displayName(group.structureId())),
                Components.placeholder("range", group.rangeDisplay()),
                Components.placeholder("status", status))));
        }

        sender.sendMessage(AdminTuiBuilder.divider());
    }

    public void refresh(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        UUID groupId = parseGroupId(sender, ctx.get("groupId"));
        if (groupId == null) return;
        regenerate(sender, groupId);
    }

    /** Shared by `/cr struct refresh <groupId>` and `/cr regen structure`. */
    public void regenerate(Commander sender, UUID groupId) {
        var target = structureService.prepareRegeneration(groupId);
        if (target.status() != StructureService.RegenerationStatus.READY) {
            sender.sendMessage(switch (target.status()) {
                case REGEN_BUSY -> Component.text(messages.text("regen-busy")).color(AdminTuiBuilder.SEVERE);
                case NOT_FOUND -> Component.text(messages.text("structure-not-found")).color(AdminTuiBuilder.SEVERE);
                case WORLD_NOT_ALLOWED -> messages.scan.worldNotAllowed.withPlaceholders(
                    Components.placeholder("world", target.group().world()));
                case CLAIM_BLOCKED -> Component.text(messages.text("structure-claim-blocked")).color(AdminTuiBuilder.SEVERE);
                case READY -> Component.empty();
            });
            return;
        }
        var g = target.group();
        var chunks = target.chunks();

        sender.sendMessage(messages.structure.refreshStart.withPlaceholders(
            Components.placeholder("structure_name", StructureRegistry.displayName(g.structureId())),
            Components.placeholder("count", String.valueOf(chunks.size()))));

        bulkReset.accept(sender, chunks);
    }

    public void unblock(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        UUID groupId = parseGroupId(sender, ctx.get("groupId"));
        if (groupId == null) return;

        boolean ok = structureService.unblock(groupId);
        sender.sendMessage(ok
            ? Component.text(messages.text("structure-unblocked")).color(AdminTuiBuilder.NORMAL)
            : Component.text(messages.text("structure-not-found")).color(AdminTuiBuilder.SEVERE));
    }

    public void block(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        UUID groupId = parseGroupId(sender, ctx.get("groupId"));
        if (groupId == null) return;

        boolean ok = structureService.block(groupId);
        sender.sendMessage(ok
            ? Component.text(messages.text("structure-blocked")).color(AdminTuiBuilder.NORMAL)
            : Component.text(messages.text("structure-not-found")).color(AdminTuiBuilder.SEVERE));
    }

    public void reset(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        UUID groupId = parseGroupId(sender, ctx.get("groupId"));
        if (groupId == null) return;

        boolean ok = structureService.reset(groupId);
        sender.sendMessage(ok
            ? Component.text(messages.text("structure-reset")).color(AdminTuiBuilder.NORMAL)
            : Component.text(messages.text("structure-not-found")).color(AdminTuiBuilder.SEVERE));
    }

    public void resetAll(CommandContext<Commander> ctx, boolean confirmed) {
        var sender = ctx.sender();
        if (confirmed) {
            int size = structureService.resetAll();
            sender.sendMessage(Component.text(messages.text("structure-reset-all", size)).color(AdminTuiBuilder.NORMAL));
            return;
        }

        sender.sendMessage(Component.text(messages.text("structure-reset-all-confirm")).color(net.kyori.adventure.text.format.TextColor.color(0xFFAA00)));
    }

    private UUID parseGroupId(Commander sender, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text(messages.text("structure-id-invalid")).color(AdminTuiBuilder.SEVERE));
            return null;
        }
    }


}

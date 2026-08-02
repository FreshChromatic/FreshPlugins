package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.presentation.command.TuningCommands;
import github.freshchromatic.chunkrevive.presentation.command.StatusCommands;
import github.freshchromatic.chunkrevive.presentation.command.StructureCommands;
import github.freshchromatic.chunkrevive.presentation.command.MarkCommands;
import github.freshchromatic.chunkrevive.presentation.command.ScanCommands;
import github.freshchromatic.chunkrevive.presentation.command.ResetCommands;
import github.freshchromatic.chunkrevive.presentation.command.AdminCommands;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.FreshLibCommander;
import github.freshchromatic.freshlib.command.PlayerCommander;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.brigadier.suggestion.TooltipSuggestion;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.help.HelpQuery;
import org.incendo.cloud.help.result.MultipleCommandResult;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class ChunkReviveCommand {

    private final github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private Messages messages;
    private final StructureRegistry structureRegistry;
    private final WorldAccessPolicy worldAccessPolicy;
    private final DiskChunkScanner scanner;
    private final DeletionService deletionService;
    private final ConfirmationManager confirmationManager = new ConfirmationManager();
    private final TuningCommands tuningCommands;
    private final StatusCommands statusCommands;
    private final StructureCommands structureCommands;
    private final MarkCommands markCommands;
    private final ScanCommands scanCommands;
    private final ResetCommands maintenanceCommands;
    private final AdminCommands adminCommands;
    private final CommandManager<Commander> commandManager;
    private final MinecraftHelp<Commander> minecraftHelp;

    public void setMessages(Messages messages) {
        this.messages = messages;
        this.tuningCommands.setMessages(messages);
        this.statusCommands.setMessages(messages);
        this.structureCommands.setMessages(messages);
        this.markCommands.setMessages(messages);
        this.scanCommands.setMessages(messages);
        this.maintenanceCommands.setMessages(messages);
        this.adminCommands.setMessages(messages);
    }

    public ChunkReviveCommand(github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin plugin, MarkRegistry markRegistry, Messages messages,
                      StructureRegistry structureRegistry, WorldAccessPolicy worldAccessPolicy, DiskChunkScanner scanner,
                      DeletionService deletionService, MarkService markService,
                      ChunkScanService chunkScanService, ResetService resetService,
                      StructureService structureService) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.messages = messages;
        this.structureRegistry = structureRegistry;
        this.worldAccessPolicy = worldAccessPolicy;
        this.scanner = scanner;
        this.deletionService = deletionService;
        this.tuningCommands = new TuningCommands(plugin, markRegistry, scanner, confirmationManager, messages);
        this.statusCommands = new StatusCommands(
            plugin, markRegistry, structureRegistry, scanner, deletionService, messages);
        this.adminCommands = new AdminCommands(
            plugin, markRegistry, scanner, deletionService, messages);
        this.maintenanceCommands = new ResetCommands(
            plugin, markRegistry, deletionService,
            confirmationManager, this::regenerateStructure, resetService, messages);
        this.structureCommands = new StructureCommands(
            structureService, maintenanceCommands::bulkRegenerate, messages);
        this.markCommands = new MarkCommands(plugin, markService, messages);
        this.scanCommands = new ScanCommands(
            plugin, worldAccessPolicy, chunkScanService, maintenanceCommands::bulkRegenerate,
            confirmationManager, deletionService, messages);

        final SenderMapper<CommandSourceStack, Commander> senderMapper =
            SenderMapper.create(FreshLibCommander::from, c -> ((FreshLibCommander) c).stack());

        this.commandManager = PaperCommandManager.builder(senderMapper)
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin);

        this.minecraftHelp = MinecraftHelp.<Commander>builder()
            .commandManager(this.commandManager)
            .audienceProvider(AudienceProvider.nativeAudience())
            .commandPrefix("/cr help")
            .colors(MinecraftHelp.helpColors(
                TextColor.fromHexString("#0044AA"),
                NamedTextColor.WHITE,
                TextColor.fromHexString("#0099FF"),
                NamedTextColor.GRAY,
                NamedTextColor.DARK_GRAY
            ))
            .build();
    }

    private void regenerateStructure(Commander sender, UUID groupId) {
        structureCommands.regenerate(sender, groupId);
    }

    /**
     * Keeps a child-command query distinct from an executable command with the same path.
     * MinecraftHelp uses a trailing space for this distinction, but its page button omits it.
     */
    private String normalizeHelpQuery(String query, Commander sender) {
        int pageSeparator = query.lastIndexOf(' ');
        if (pageSeparator > 0 && isPageNumber(query.substring(pageSeparator + 1))) {
            String topic = query.substring(0, pageSeparator);
            if (isChildCommandListing(topic, sender)) {
                return topic + " " + query.substring(pageSeparator);
            }
            return query;
        }
        return !query.isEmpty() && isChildCommandListing(query, sender) ? query + " " : query;
    }

    private boolean isPageNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isChildCommandListing(String query, Commander sender) {
        return minecraftHelp.helpHandler().query(HelpQuery.of(sender, query + " ")) instanceof MultipleCommandResult;
    }

    public void register() {
        var root = commandManager.commandBuilder("cr");

        // /cr (shows help index)
        commandManager.command(root
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("", ctx.sender())));

        // /cr help [query]
        commandManager.command(root.literal("help")
            .permission("chunkrevive.admin")
            .optional("query", StringParser.greedyStringParser())
            .commandDescription(Description.of(messages.descriptions.help))
            .handler(ctx -> {
                String query = ctx.getOrDefault("query", "");
                // MinecraftHelp uses a bare number as the page for the root command index.
                // Prefixing it with "cr" turns the request into help for /cr instead.
                if (!query.isEmpty() && !query.matches("\\d+") && !query.startsWith("cr")) {
                    query = "cr " + query;
                }
                minecraftHelp.queryCommands(normalizeHelpQuery(query, ctx.sender()), ctx.sender());
            }));

        // /cr mark (shows mark subcommands help)
        commandManager.command(root.literal("mark")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr mark ", ctx.sender())));

        var markNode = root.literal("mark");
        var markHereNode = markNode.literal("here").permission("chunkrevive.admin").senderType(PlayerCommander.class);

        // /cr mark here biome <radius> — players only, marks the biome the player is currently
        // standing in within <radius> chunks (see resolveBiomeIdsArg's "here" keyword).
        commandManager.command(markHereNode.literal("biome")
            .commandDescription(Description.of(messages.descriptions.markHereBiome))
            .handler(scanCommands::markHereBiome));

        // /cr mark here struct — players only, detects and marks nearby tracked structures
        commandManager.command(markHereNode.literal("struct")
            .commandDescription(Description.of(messages.descriptions.markHereStruct))
            .handler(structureCommands::markHere));

        // /cr mark here — players only, current chunk
        commandManager.command(markHereNode
            .commandDescription(Description.of(messages.descriptions.markMark)) // uses same desc
            .handler(markCommands::mark));

        // /cr mark chunk <world> <cx> <cz> — specific chunk
        commandManager.command(markNode.literal("chunk")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .commandDescription(Description.of(messages.descriptions.markCoords))
            .handler(markCommands::markCoordinates));

        // /cr mark follow
        commandManager.command(markNode.literal("follow")
            .permission("chunkrevive.admin")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.markFollow))
            .handler(markCommands::markFollow));

        // /cr mark list [page]
        commandManager.command(markNode.literal("list")
            .permission("chunkrevive.admin")
            .optional("page", IntegerParser.integerParser(1))
            .commandDescription(Description.of(messages.descriptions.markList))
            .handler(markCommands::list));

        // /cr mark resetmark <world>
        commandManager.command(markNode.literal("resetmark")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .commandDescription(Description.of(messages.descriptions.markResetmark))
            .handler(ctx -> markCommands.resetMarks(ctx, false)));

        commandManager.command(markNode.literal("resetmark")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.markResetmarkConfirm))
            .handler(ctx -> markCommands.resetMarks(ctx, true)));

        // /cr mark fullmark <world|all>
        commandManager.command(markNode.literal("fullmark")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), fullmarkWorldSuggestions())
            .commandDescription(Description.of(messages.descriptions.markFullmark))
            .handler(ctx -> scanCommands.fullMark(ctx, false)));

        commandManager.command(markNode.literal("fullmark")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), fullmarkWorldSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.markFullmarkConfirm))
            .handler(ctx -> scanCommands.fullMark(ctx, true)));

        // /cr mark radius <world> <radius> [x] [z]
        commandManager.command(markNode.literal("radius")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("radius", IntegerParser.integerParser(0))
            .optional("x", IntegerParser.integerParser(), blockXSuggestions())
            .optional("z", IntegerParser.integerParser(), blockZSuggestions())
            .commandDescription(Description.of(messages.descriptions.markRadius))
            .handler(scanCommands::radiusMark));

        // /cr mark biomefull <world> <biomeIds>
        commandManager.command(markNode.literal("biomefull")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("biomeIds", StringParser.stringParser(), scanCommands.biomeIdSuggestions())
            .commandDescription(Description.of(messages.descriptions.markBiomefull))
            .handler(ctx -> scanCommands.biomeFullMark(ctx, false)));

        commandManager.command(markNode.literal("biomefull")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("biomeIds", StringParser.stringParser(), scanCommands.biomeIdSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.markBiomefullConfirm))
            .handler(ctx -> scanCommands.biomeFullMark(ctx, true)));

        // /cr mark biomeradius <world> <biomeIds> <radius> [x] [z]
        commandManager.command(markNode.literal("biomeradius")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("biomeIds", StringParser.stringParser(), scanCommands.biomeIdSuggestions())
            .required("radius", IntegerParser.integerParser(0))
            .optional("x", IntegerParser.integerParser(), blockXSuggestions())
            .optional("z", IntegerParser.integerParser(), blockZSuggestions())
            .commandDescription(Description.of(messages.descriptions.markBiomeradius))
            .handler(scanCommands::biomeRadiusMark));

        // /cr unmark (shows unmark subcommands help)
        commandManager.command(root.literal("unmark")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr unmark ", ctx.sender())));

        var unmarkNode = root.literal("unmark");

        // /cr unmark here
        commandManager.command(unmarkNode.literal("here")
            .permission("chunkrevive.admin")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.unmarkHere))
            .handler(markCommands::unmark));

        // /cr unmark chunk <world> <cx> <cz>
        commandManager.command(unmarkNode.literal("chunk")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .commandDescription(Description.of(messages.descriptions.unmarkChunk))
            .handler(markCommands::unmarkCoordinates));

        // /cr unmark follow
        commandManager.command(unmarkNode.literal("follow")
            .permission("chunkrevive.admin")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.unmarkFollow))
            .handler(markCommands::unmarkFollow));

        // /cr regen (shows regen subcommands help)
        commandManager.command(root.literal("regen")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr regen ", ctx.sender())));

        var regenNode = root.literal("regen");

        var regenHereNode = regenNode.literal("here").permission("chunkrevive.admin").senderType(PlayerCommander.class);

        // /cr regen here
        commandManager.command(regenHereNode
            .commandDescription(Description.of(messages.descriptions.regenHere))
            .handler(maintenanceCommands::regenerateCurrent));

        // /cr regen here struct (moved from the former top-level /cr regen struct)
        commandManager.command(regenHereNode.literal("struct")
            .commandDescription(Description.of(messages.descriptions.regenStruct))
            .handler(ctx -> maintenanceCommands.regenerateCurrentStructure(ctx, false)));

        commandManager.command(regenHereNode.literal("struct")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.regenHereStructConfirm))
            .handler(ctx -> maintenanceCommands.regenerateCurrentStructure(ctx, true)));

        // /cr regen here biome <radius> — marks (if needed) and immediately regenerates the biome
        // the player is currently standing in within <radius> chunks, mirroring "regen here struct".
        commandManager.command(regenHereNode.literal("biome")
            .commandDescription(Description.of(messages.descriptions.regenHereBiome))
            .handler(ctx -> scanCommands.regenerateHereBiome(ctx, false)));

        commandManager.command(regenHereNode.literal("biome")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.regenHereBiomeConfirm))
            .handler(ctx -> scanCommands.regenerateHereBiome(ctx, true)));

        // /cr regen chunk <world> <cx> <cz>
        commandManager.command(regenNode.literal("chunk")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .commandDescription(Description.of(messages.descriptions.regenChunk))
            .handler(maintenanceCommands::regenerateCoordinates));

        // /cr regen all chunks|structures|all
        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("chunks")
            .commandDescription(Description.of(messages.descriptions.regenAllChunks))
            .handler(ctx -> maintenanceCommands.regenerateAllChunks(ctx, false)));

        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("structures")
            .commandDescription(Description.of(messages.descriptions.regenAllStructures))
            .handler(ctx -> maintenanceCommands.regenerateAllStructures(ctx, false)));

        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("all")
            .commandDescription(Description.of(messages.descriptions.regenAllAll))
            .handler(ctx -> maintenanceCommands.regenerateAll(ctx, false)));

        // /cr regen all chunks|structures|all confirm
        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("chunks")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.regenAllChunksConfirm))
            .handler(ctx -> maintenanceCommands.regenerateAllChunks(ctx, true)));

        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("structures")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.regenAllStructuresConfirm))
            .handler(ctx -> maintenanceCommands.regenerateAllStructures(ctx, true)));

        commandManager.command(regenNode.literal("all")
            .permission("chunkrevive.admin")
            .literal("all")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.regenAllAllConfirm))
            .handler(ctx -> maintenanceCommands.regenerateAll(ctx, true)));

        // /cr reset applies reset-strategy. Unlike /cr regen, it may intentionally select
        // DELETE_CHUNK or DELETE_REGION and therefore confirms every destructive plan.
        commandManager.command(root.literal("reset")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr reset ", ctx.sender())));
        var resetNode = root.literal("reset");
        var resetHereNode = resetNode.literal("here").permission("chunkrevive.admin")
            .senderType(PlayerCommander.class);
        commandManager.command(resetHereNode
            .commandDescription(Description.of(messages.descriptions.resetHere))
            .handler(ctx -> maintenanceCommands.resetCurrent(ctx, false)));
        commandManager.command(resetHereNode.literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetHereConfirm))
            .handler(ctx -> maintenanceCommands.resetCurrent(ctx, true)));
        commandManager.command(resetHereNode.literal("struct")
            .commandDescription(Description.of(messages.descriptions.resetHereStruct))
            .handler(ctx -> maintenanceCommands.resetCurrentStructure(ctx, false)));
        commandManager.command(resetHereNode.literal("struct").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetHereStructConfirm))
            .handler(ctx -> maintenanceCommands.resetCurrentStructure(ctx, true)));
        commandManager.command(resetHereNode.literal("biome")
            .commandDescription(Description.of(messages.descriptions.resetHereBiome))
            .handler(ctx -> scanCommands.resetHereBiome(ctx, false, maintenanceCommands)));
        commandManager.command(resetHereNode.literal("biome").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetHereBiomeConfirm))
            .handler(ctx -> scanCommands.resetHereBiome(ctx, true, maintenanceCommands)));

        var resetChunkNode = resetNode.literal("chunk").permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions());
        commandManager.command(resetChunkNode
            .commandDescription(Description.of(messages.descriptions.resetChunk))
            .handler(ctx -> maintenanceCommands.resetCoordinates(ctx, false)));
        commandManager.command(resetChunkNode.literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetChunkConfirm))
            .handler(ctx -> maintenanceCommands.resetCoordinates(ctx, true)));

        var resetAllNode = resetNode.literal("all").permission("chunkrevive.admin");
        commandManager.command(resetAllNode.literal("chunks")
            .commandDescription(Description.of(messages.descriptions.resetAllChunks))
            .handler(ctx -> maintenanceCommands.resetAllChunks(ctx, false)));
        commandManager.command(resetAllNode.literal("structures")
            .commandDescription(Description.of(messages.descriptions.resetAllStructures))
            .handler(ctx -> maintenanceCommands.resetAllStructures(ctx, false)));
        commandManager.command(resetAllNode.literal("all")
            .commandDescription(Description.of(messages.descriptions.resetAllAll))
            .handler(ctx -> maintenanceCommands.resetAll(ctx, false)));
        commandManager.command(resetAllNode.literal("chunks").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetAllChunksConfirm))
            .handler(ctx -> maintenanceCommands.resetAllChunks(ctx, true)));
        commandManager.command(resetAllNode.literal("structures").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetAllStructuresConfirm))
            .handler(ctx -> maintenanceCommands.resetAllStructures(ctx, true)));
        commandManager.command(resetAllNode.literal("all").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.resetAllAllConfirm))
            .handler(ctx -> maintenanceCommands.resetAll(ctx, true)));

        // Destructive storage operations are deliberately separate from /cr regen so existing
        // scripts and operator muscle-memory can never expand a one-chunk regeneration into a purge.
        commandManager.command(root.literal("delete")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr delete ", ctx.sender())));
        var deleteChunkNode = root.literal("delete").literal("chunk").permission("chunkrevive.admin");
        commandManager.command(deleteChunkNode.literal("here")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.deleteChunkHere))
            .handler(maintenanceCommands::deleteChunkHere));
        commandManager.command(deleteChunkNode
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .commandDescription(Description.of(messages.descriptions.deleteChunkCoords))
            .handler(maintenanceCommands::deleteChunkCoordinates));
        commandManager.command(deleteChunkNode.literal("all")
            .commandDescription(Description.of(messages.descriptions.deleteChunkAll))
            .handler(ctx -> maintenanceCommands.deleteChunkAll(ctx, false)));
        commandManager.command(deleteChunkNode.literal("all").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.deleteChunkAll))
            .handler(ctx -> maintenanceCommands.deleteChunkAll(ctx, true)));

        var deleteHereNode = root.literal("delete").literal("here")
            .permission("chunkrevive.admin").senderType(PlayerCommander.class);
        commandManager.command(deleteHereNode
            .commandDescription(Description.of(messages.descriptions.deleteHereChunk))
            .handler(maintenanceCommands::deleteChunkHere));
        commandManager.command(deleteHereNode.literal("chunk")
            .commandDescription(Description.of(messages.descriptions.deleteHereChunk))
            .handler(maintenanceCommands::deleteChunkHere));
        commandManager.command(deleteHereNode.literal("struct")
            .commandDescription(Description.of(messages.descriptions.deleteHereStruct))
            .handler(ctx -> maintenanceCommands.deleteHereStructure(ctx, false)));
        commandManager.command(deleteHereNode.literal("struct").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.deleteHereStruct))
            .handler(ctx -> maintenanceCommands.deleteHereStructure(ctx, true)));
        commandManager.command(deleteHereNode.literal("biome")
            .commandDescription(Description.of(messages.descriptions.deleteHereBiome))
            .handler(ctx -> scanCommands.deleteHereBiome(ctx, false)));
        commandManager.command(deleteHereNode.literal("biome").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.deleteHereBiome))
            .handler(ctx -> scanCommands.deleteHereBiome(ctx, true)));

        var deleteMarkedNode = root.literal("delete").literal("marked").permission("chunkrevive.admin");
        commandManager.command(deleteMarkedNode
            .commandDescription(Description.of(messages.descriptions.deleteMarked))
            .handler(ctx -> maintenanceCommands.deleteMarked(ctx, null, false)));
        commandManager.command(deleteMarkedNode.literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.deleteMarked))
            .handler(ctx -> maintenanceCommands.deleteMarked(ctx, null, true)));
        commandManager.command(deleteMarkedNode
            .required("world", StringParser.stringParser(), worldSuggestions())
            .commandDescription(Description.of(messages.descriptions.deleteMarkedWorld))
            .handler(ctx -> maintenanceCommands.deleteMarked(ctx, ctx.get("world"), false)));
        commandManager.command(deleteMarkedNode
            .required("world", StringParser.stringParser(), worldSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.deleteMarkedWorld))
            .handler(ctx -> maintenanceCommands.deleteMarked(ctx, ctx.get("world"), true)));

        commandManager.command(root.literal("prune")
            .permission("chunkrevive.admin")
            .handler(ctx -> minecraftHelp.queryCommands("cr prune ", ctx.sender())));
        var pruneEmptyNode = root.literal("prune").literal("empty")
            .permission("chunkrevive.admin")
            .required("world", StringParser.stringParser(), worldSuggestions());
        commandManager.command(pruneEmptyNode
            .commandDescription(Description.of(messages.descriptions.pruneEmpty))
            .handler(ctx -> maintenanceCommands.pruneEmpty(ctx, false)));
        commandManager.command(pruneEmptyNode.literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.pruneEmpty))
            .handler(ctx -> maintenanceCommands.pruneEmpty(ctx, true)));
        var pruneRegionNode = root.literal("prune").literal("region").permission("chunkrevive.admin");
        commandManager.command(pruneRegionNode.literal("here")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.pruneRegionHere))
            .handler(ctx -> maintenanceCommands.pruneRegionHere(ctx, false)));
        commandManager.command(pruneRegionNode.literal("here").literal("--confirm")
            .senderType(PlayerCommander.class)
            .commandDescription(Description.of(messages.descriptions.pruneRegionHere))
            .handler(ctx -> maintenanceCommands.pruneRegionHere(ctx, true)));
        commandManager.command(pruneRegionNode
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("rx", IntegerParser.integerParser())
            .required("rz", IntegerParser.integerParser())
            .commandDescription(Description.of(messages.descriptions.pruneRegionCoords))
            .handler(ctx -> maintenanceCommands.pruneRegionCoordinates(ctx, false)));
        commandManager.command(pruneRegionNode
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("rx", IntegerParser.integerParser())
            .required("rz", IntegerParser.integerParser())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.pruneRegionCoords))
            .handler(ctx -> maintenanceCommands.pruneRegionCoordinates(ctx, true)));
        commandManager.command(pruneRegionNode.literal("chunk")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .commandDescription(Description.of(messages.descriptions.pruneRegionChunk))
            .handler(ctx -> maintenanceCommands.pruneRegionByChunk(ctx, false)));
        commandManager.command(pruneRegionNode.literal("chunk")
            .required("world", StringParser.stringParser(), worldSuggestions())
            .required("cx", IntegerParser.integerParser(), chunkXSuggestions())
            .required("cz", IntegerParser.integerParser(), chunkZSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.pruneRegionChunk))
            .handler(ctx -> maintenanceCommands.pruneRegionByChunk(ctx, true)));
        commandManager.command(pruneRegionNode.literal("all")
            .commandDescription(Description.of(messages.descriptions.pruneRegionAll))
            .handler(ctx -> maintenanceCommands.pruneRegionAll(ctx, false)));
        commandManager.command(pruneRegionNode.literal("all").literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.pruneRegionAll))
            .handler(ctx -> maintenanceCommands.pruneRegionAll(ctx, true)));

        // /cr status
        commandManager.command(root.literal("status")
            .permission("chunkrevive.admin")
            .optional("page", IntegerParser.integerParser(1))
            .commandDescription(Description.of(messages.descriptions.status))
            .handler(statusCommands::status));

        var serverNode = root.literal("server");

        // /cr server
        commandManager.command(serverNode
            .permission("chunkrevive.admin")
            .commandDescription(Description.of(messages.descriptions.server))
            .handler(statusCommands::serverStatus));

        // /cr server regions [world]
        commandManager.command(serverNode.literal("regions")
            .permission("chunkrevive.admin")
            .optional("world", StringParser.stringParser(), worldSuggestions())
            .commandDescription(Description.of(messages.descriptions.regions))
            .handler(adminCommands::regionsCount));

        var tuneNode = root.literal("tune").permission("chunkrevive.admin");
        commandManager.command(tuneNode
            .commandDescription(Description.of(messages.descriptions.tune))
            .handler(tuningCommands::overview));
        commandManager.command(tuneNode.literal("preview")
            .required("profile", StringParser.stringParser(), tuningProfileSuggestions())
            .commandDescription(Description.of(messages.descriptions.tune))
            .handler(tuningCommands::preview));
        commandManager.command(tuneNode.literal("apply")
            .required("profile", StringParser.stringParser(), tuningProfileSuggestions())
            .commandDescription(Description.of(messages.descriptions.tune))
            .handler(ctx -> tuningCommands.apply(ctx, false)));
        commandManager.command(tuneNode.literal("apply")
            .required("profile", StringParser.stringParser(), tuningProfileSuggestions())
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.tune))
            .handler(ctx -> tuningCommands.apply(ctx, true)));

        // /cr cancel
        commandManager.command(root.literal("cancel")
            .permission("chunkrevive.admin")
            .commandDescription(Description.of(messages.descriptions.cancel))
            .handler(adminCommands::cancel));

        // /cr reload
        commandManager.command(root.literal("reload")
            .permission("chunkrevive.admin")
            .commandDescription(Description.of(messages.descriptions.reload))
            .handler(adminCommands::reload));

        var structNode = root.literal("struct", "structure");

        // /cr struct (shows struct subcommands help)
        commandManager.command(structNode
            .permission("chunkrevive.keep")
            .handler(ctx -> minecraftHelp.queryCommands("cr struct ", ctx.sender())));

        // /cr struct check
        commandManager.command(structNode
            .permission("chunkrevive.keep")
            .senderType(PlayerCommander.class)
            .literal("check")
            .commandDescription(Description.of(messages.descriptions.structCheck))
            .handler(structureCommands::check));

        // /cr struct list [page]
        commandManager.command(structNode
            .permission("chunkrevive.keep")
            .literal("list")
            .optional("page", IntegerParser.integerParser(1))
            .commandDescription(Description.of(messages.descriptions.structList))
            .handler(structureCommands::list));

        // /cr struct regen <groupId>
        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("regen")
            .required("groupId", StringParser.stringParser(), groupSuggestions())
            .commandDescription(Description.of(messages.descriptions.structRegen))
            .handler(structureCommands::refresh));

        // /cr struct unblock <groupId>
        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("unblock")
            .required("groupId", StringParser.stringParser(), groupSuggestions())
            .commandDescription(Description.of(messages.descriptions.structUnblock))
            .handler(structureCommands::unblock));

        // /cr struct block <groupId>
        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("block")
            .required("groupId", StringParser.stringParser(), groupSuggestions())
            .commandDescription(Description.of(messages.descriptions.structBlock))
            .handler(structureCommands::block));

        // /cr struct reset <groupId>
        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("reset")
            .required("groupId", StringParser.stringParser(), groupSuggestions())
            .commandDescription(Description.of(messages.descriptions.structReset))
            .handler(structureCommands::reset));

        // /cr struct resetall
        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("resetall")
            .commandDescription(Description.of(messages.descriptions.structResetAll))
            .handler(ctx -> structureCommands.resetAll(ctx, false)));

        commandManager.command(structNode
            .permission("chunkrevive.admin")
            .literal("resetall")
            .literal("--confirm")
            .commandDescription(Description.of(messages.descriptions.structResetAllConfirm))
            .handler(ctx -> structureCommands.resetAll(ctx, true)));
    }

    // ── Handlers ─────────────────────────────────────────────────────────────


    private static BlockingSuggestionProvider.Strings<Commander> worldSuggestions() {
        return (ctx, input) -> Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList();
    }

    private static BlockingSuggestionProvider.Strings<Commander> tuningProfileSuggestions() {
        return (ctx, input) -> List.of("optimized", "balanced", "performance");
    }

    private static BlockingSuggestionProvider.Strings<Commander> fullmarkWorldSuggestions() {
        return (ctx, input) -> {
            var worlds = new ArrayList<String>();
            worlds.add("all");
            Bukkit.getWorlds().stream().map(org.bukkit.World::getName).forEach(worlds::add);
            return worlds;
        };
    }

    private static BlockingSuggestionProvider.Strings<Commander> chunkXSuggestions() {
        return (ctx, input) -> {
            if (ctx.sender() instanceof PlayerCommander pc) {
                return List.of(String.valueOf(pc.player().getLocation().getChunk().getX()));
            }
            return List.of();
        };
    }

    private static BlockingSuggestionProvider.Strings<Commander> chunkZSuggestions() {
        return (ctx, input) -> {
            if (ctx.sender() instanceof PlayerCommander pc) {
                return List.of(String.valueOf(pc.player().getLocation().getChunk().getZ()));
            }
            return List.of();
        };
    }

    private static BlockingSuggestionProvider.Strings<Commander> blockXSuggestions() {
        return (ctx, input) -> {
            if (ctx.sender() instanceof PlayerCommander pc) {
                return List.of(String.valueOf(pc.player().getLocation().getBlockX()));
            }
            return List.of();
        };
    }

    private static BlockingSuggestionProvider.Strings<Commander> blockZSuggestions() {
        return (ctx, input) -> {
            if (ctx.sender() instanceof PlayerCommander pc) {
                return List.of(String.valueOf(pc.player().getLocation().getBlockZ()));
            }
            return List.of();
        };
    }

    private BlockingSuggestionProvider<Commander> groupSuggestions() {
        return (ctx, input) -> {
            if (structureRegistry == null) {
                return List.of();
            }
            List<Suggestion> list = new ArrayList<>();
            for (var g : structureRegistry.getAllGroups()) {
                String uuidStr = g.groupId().toString();
                String displayName = StructureRegistry.displayName(g.structureId());
                String details = "%s (%s)".formatted(displayName, g.rangeDisplay());
                com.mojang.brigadier.Message tooltipMsg = MessageComponentSerializer.message()
                    .serialize(Component.text(details));
                list.add(TooltipSuggestion.suggestion(uuidStr, tooltipMsg));
            }
            return list;
        };
    }
}

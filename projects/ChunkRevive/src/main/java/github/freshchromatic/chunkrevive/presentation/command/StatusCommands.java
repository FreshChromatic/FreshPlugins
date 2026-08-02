package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.structure.StructureRegistry;
import github.freshchromatic.freshlib.command.Commander;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.incendo.cloud.context.CommandContext;

/** Renders queue, deletion, scan and server runtime status commands. */
public final class StatusCommands {
    private static final int PAGE_SIZE = 8;

    private final ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private final StructureRegistry structureRegistry;
    private final DiskChunkScanner scanner;
    private final DeletionService deletionService;
    private Messages messages;

    public StatusCommands(
            ChunkRevivePlugin plugin,
            MarkRegistry markRegistry,
            StructureRegistry structureRegistry,
            DiskChunkScanner scanner,
            DeletionService deletionService,
            Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.structureRegistry = structureRegistry;
        this.scanner = scanner;
        this.deletionService = deletionService;
        this.messages = messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void status(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        var queue = markRegistry.getRegenerationQueue();
        int deletionPage = ctx.getOrDefault("page", 1);

        sender.sendMessage(AdminTuiBuilder.header(Component.text(messages.text("status-regen-title"))));
        sender.sendMessage(AdminTuiBuilder.divider());

        if (!queue.isRunning()) {
            sender.sendMessage(AdminTuiBuilder.line(
                Component.text(messages.text("status-label", messages.text("status-current"))).color(AdminTuiBuilder.SECONDARY)
                    .append(Component.text(messages.text("status-idle")).color(AdminTuiBuilder.SECONDARY))
            ));
            sender.sendMessage(AdminTuiBuilder.divider());
            sendScanStatus(sender);
            sendDeletionStatus(sender, deletionPage);
            return;
        }

        int completed = queue.getCompletedCount();
        int total = queue.getTotalCount();
        int percent = total > 0 ? (int) ((long) completed * 100 / total) : 0;
        int active = queue.getActiveTasks();
        int pending = queue.getPendingCount();

        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-current"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(queue.isCancelled() ? messages.text("status-cancelling") : messages.text("status-running")).color(queue.isCancelled() ? AdminTuiBuilder.SEVERE : AdminTuiBuilder.NORMAL))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-completed"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(completed + " / " + total + " (" + percent + "%)").color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-concurrency"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(active).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-pending"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(pending).color(AdminTuiBuilder.ACTION))
        ));

        if (!queue.isCancelled()) {
            var cancelBtn = AdminTuiBuilder.severeButton(
                Component.text(messages.text("status-cancel-regen")),
                ClickEvent.runCommand("/cr cancel"),
                Component.text(messages.text("status-cancel-regen-hover")).color(AdminTuiBuilder.SECONDARY)
            );
            sender.sendMessage(
                Component.text()
                    .append(Component.text("▸ ").color(AdminTuiBuilder.PRIMARY))
                    .append(cancelBtn)
                    .build()
            );
        }
        sender.sendMessage(AdminTuiBuilder.divider());
        sendScanStatus(sender);
        sendDeletionStatus(sender, deletionPage);
    }

    private void sendDeletionStatus(Commander sender, int requestedPage) {
        sender.sendMessage(AdminTuiBuilder.header(Component.text(messages.text("status-deletion-title"))));
        sender.sendMessage(AdminTuiBuilder.divider());
        var jobs = deletionService.snapshots();
        if (jobs.isEmpty()) {
            sender.sendMessage(messages.deletion.noJobs.asComponent());
            sender.sendMessage(AdminTuiBuilder.divider());
            return;
        }
        int totalPages = Math.max(1, (int) Math.ceil(jobs.size() / (double) PAGE_SIZE));
        int page = Math.clamp(requestedPage, 1, totalPages);
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, jobs.size());
        for (var job : jobs.subList(from, to)) {
            String state = switch (job.state()) {
                case WAITING_FOR_COLD -> messages.text("status-waiting-cold");
                case RUNNING -> messages.text("status-running");
                case FAILED -> messages.text("status-failed");
            };
            Component line = Component.text(job.shortId() + "  ").color(AdminTuiBuilder.PRIMARY)
                .append(Component.text(job.world() + " " + job.targetDisplay()).color(AdminTuiBuilder.ACTION))
                .append(job.restored() ? Component.text(messages.text("status-restored")).color(AdminTuiBuilder.SECONDARY) : Component.empty())
                .append(Component.text("  " + state).color(
                    job.state() == DeletionService.State.FAILED ? AdminTuiBuilder.SEVERE : AdminTuiBuilder.SECONDARY));
            sender.sendMessage(AdminTuiBuilder.line(line));
            if (job.failure() != null) {
                sender.sendMessage(AdminTuiBuilder.line(Component.text("  " + job.failure()).color(AdminTuiBuilder.SEVERE)));
            } else if (job.waitingReason() != null) {
                sender.sendMessage(AdminTuiBuilder.line(Component.text("  " + job.waitingReason()).color(AdminTuiBuilder.SECONDARY)));
            }
        }
        sender.sendMessage(AdminTuiBuilder.divider());
        if (totalPages > 1) {
            var prevBtn = page > 1
                ? AdminTuiBuilder.normalButton(Component.text(messages.text("status-previous")),
                    ClickEvent.runCommand("/cr status " + (page - 1)), Component.empty())
                : AdminTuiBuilder.disabledButton(Component.text(messages.text("status-previous")));
            var nextBtn = page < totalPages
                ? AdminTuiBuilder.normalButton(Component.text(messages.text("status-next")),
                    ClickEvent.runCommand("/cr status " + (page + 1)), Component.empty())
                : AdminTuiBuilder.disabledButton(Component.text(messages.text("status-next")));
            sender.sendMessage(AdminTuiBuilder.actionBar(Component.text()
                .append(prevBtn)
                .append(Component.text("  " + page + " / " + totalPages + "  ").color(AdminTuiBuilder.SECONDARY))
                .append(nextBtn)
                .build()));
            sender.sendMessage(AdminTuiBuilder.line(Component.text(messages.text("status-page-info", from + 1, to, jobs.size()))
                .color(AdminTuiBuilder.SECONDARY)));
        }
    }

    /** Reports the independent disk-scan queue (fullmark/radiusmark), separate from the regen batch queue above. */
    private void sendScanStatus(Commander sender) {
        sender.sendMessage(AdminTuiBuilder.header(Component.text(messages.text("status-scan-title"))));
        sender.sendMessage(AdminTuiBuilder.divider());

        if (!scanner.isRunning()) {
            sender.sendMessage(AdminTuiBuilder.line(
                Component.text(messages.text("status-label", messages.text("status-current"))).color(AdminTuiBuilder.SECONDARY)
                    .append(Component.text(messages.text("status-idle")).color(AdminTuiBuilder.SECONDARY))
            ));
            sender.sendMessage(AdminTuiBuilder.divider());
            return;
        }

        int scanned = scanner.getRegionsScanned();
        int total = scanner.getRegionsTotal();
        int percent = total > 0 ? (int) ((long) scanned * 100 / total) : 0;

        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-scan-world"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(String.valueOf(scanner.getActiveWorld())).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-region-progress"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(scanned + " / " + total + " (" + percent + "%)").color(AdminTuiBuilder.ACTION))
        ));

        var cancelBtn = AdminTuiBuilder.severeButton(
            Component.text(messages.text("status-cancel-scan")),
            ClickEvent.runCommand("/cr cancel"),
            Component.text(messages.text("status-cancel-scan-hover")).color(AdminTuiBuilder.SECONDARY)
        );
        sender.sendMessage(
            Component.text()
                .append(Component.text("▸ ").color(AdminTuiBuilder.PRIMARY))
                .append(cancelBtn)
                .build()
        );
        sender.sendMessage(AdminTuiBuilder.divider());
    }

    public void serverStatus(CommandContext<Commander> ctx) {
        var sender = ctx.sender();
        var runtime = Runtime.getRuntime();

        int processors = runtime.availableProcessors();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        int activeThreads = Thread.activeCount();
        int jvmThreads = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();

        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        String uptimeStr = messages.text("status-uptime", days, hours % 24, minutes % 60, seconds % 60);

        double cpuLoad = -1.0;
        try {
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                cpuLoad = sunOsBean.getCpuLoad();
            }
        } catch (Throwable ignored) {}

        String cpuLoadStr;
        if (cpuLoad >= 0) {
            cpuLoadStr = String.format("%.1f%%", cpuLoad * 100.0);
        } else {
            double loadAvg = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            cpuLoadStr = loadAvg >= 0 ? String.format("%.2f", loadAvg) : messages.text("status-unavailable");
        }

        String bukkitVersion = Bukkit.getVersion();
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        String dbType = plugin.getPluginConfig().database.type;

        int totalMarkedChunks = markRegistry.getMarkedChunks().size();
        long structureChunks = markRegistry.getMarkedChunks().stream().filter(c -> c.structureGroupId() != null).count();
        long independentChunks = totalMarkedChunks - structureChunks;

        int totalStructures = structureRegistry != null ? structureRegistry.getAllGroups().size() : 0;
        long blockedStructures = structureRegistry != null
            ? structureRegistry.getAllGroups().stream().filter(github.freshchromatic.chunkrevive.feature.structure.StructureGroup::blocked).count()
            : 0;

        long residenceBlockedChunks = 0;
        var resHook = markRegistry.getLandProtection();
        if (resHook != null) {
            for (var c : markRegistry.getMarkedChunks()) {
                var w = Bukkit.getWorld(c.world());
                if (w != null && resHook.hasClaim(w, c.cx(), c.cz())) {
                    residenceBlockedChunks++;
                }
            }
        }

        int activeGenThreads = github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator.getActiveGenerationThreads();
        int resolvedParallelism = github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator.getResolvedParallelism();
        var threadPool = github.freshchromatic.chunkrevive.feature.regeneration.NmsTerrainGenerator.getThreadPoolConfig();
        String confParallelism = threadPool.parallelism;

        sender.sendMessage(AdminTuiBuilder.header(Component.text(messages.text("status-server-title"))));
        sender.sendMessage(AdminTuiBuilder.divider());
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-processors"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(processors).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-jvm-threads"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(jvmThreads).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-group-threads"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(activeThreads).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-gen-threads"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(messages.text("status-gen-thread-value", activeGenThreads, resolvedParallelism, confParallelism)).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-memory"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(messages.text("status-memory-value", usedMemory, totalMemory, maxMemory)).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-cpu"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(cpuLoadStr).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-uptime-label"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(uptimeStr).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-players"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(onlinePlayers).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-database"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(dbType).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-marked-chunks"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(messages.text("status-marked-chunks-value", totalMarkedChunks, independentChunks, structureChunks)).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-marked-structures"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(messages.text("status-marked-structures-value", totalStructures, blockedStructures)).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-residence-blocked"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(residenceBlockedChunks).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-version"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(bukkitVersion).color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.line(
            Component.text(messages.text("status-label", messages.text("status-os"))).color(AdminTuiBuilder.SECONDARY)
                .append(Component.text(System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")").color(AdminTuiBuilder.ACTION))
        ));
        sender.sendMessage(AdminTuiBuilder.divider());
    }


}

package github.freshchromatic.chunkrevive.presentation.display;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.tuning.ServerResourceSnapshot;
import github.freshchromatic.chunkrevive.feature.tuning.TuningProfile;
import github.freshchromatic.chunkrevive.feature.tuning.TuningRecommendation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TuningTuiBuilder {

    private TuningTuiBuilder() {}

    public static List<Component> overview(Messages.Tuning text, ServerResourceSnapshot resources,
                                            TuningProfile recommended) {
        List<Component> lines = new ArrayList<>();
        lines.add(AdminTuiBuilder.header(text.title.asComponent()));
        lines.add(AdminTuiBuilder.divider());
        lines.add(AdminTuiBuilder.line(text.hardwareTitle.asComponent()));
        lines.add(info(text, text.labelProcessors, Integer.toString(resources.logicalProcessors()), text.hoverProcessors.asComponent()));
        lines.add(info(text, text.labelHeap,
            replace(text.valueMib, "used", resources.usedHeapMiB(), "max", resources.maxHeapMiB()),
            text.hoverHeap.asComponent()));
        String systemMemory = resources.totalSystemMemoryMiB() < 0
            ? text.valueUnavailable
            : replace(text.valueSystemMemory, "free", resources.freeSystemMemoryMiB(), "total", resources.totalSystemMemoryMiB());
        lines.add(info(text, text.labelSystemMemory, systemMemory, text.hoverSystemMemory.asComponent()));
        lines.add(info(text, text.labelJvmThreads, Integer.toString(resources.jvmThreadCount()), text.hoverJvmThreads.asComponent()));
        String cpu = resources.systemCpuLoad() < 0
            ? text.valueUnavailable
            : replace(text.valuePercent, "value", String.format(Locale.ROOT, "%.1f", resources.systemCpuLoad() * 100));
        lines.add(info(text, text.labelCpuLoad, cpu, text.hoverCpuLoad.asComponent()));
        lines.add(info(text, text.labelEnvironment, resources.folia() ? text.valueFolia : text.valuePaper,
            text.hoverEnvironment.asComponent()));
        lines.add(AdminTuiBuilder.line(text.recommendedModeLine.withPlaceholders(
            Placeholder.component("mode", profileLabel(text, recommended)))));
        lines.add(AdminTuiBuilder.divider());
        lines.add(AdminTuiBuilder.actionBar(AdminTuiBuilder.row(
            modeButton(text, TuningProfile.OPTIMIZED),
            modeButton(text, TuningProfile.BALANCED),
            modeButton(text, TuningProfile.PERFORMANCE)
        )));
        return lines;
    }

    public static List<Component> preview(Messages.Tuning text, PluginConfig config,
                                           ServerResourceSnapshot resources, TuningRecommendation recommendation) {
        List<Component> lines = new ArrayList<>();
        Component mode = profileLabel(text, recommendation.profile());
        lines.add(AdminTuiBuilder.header(text.previewTitle.withPlaceholders(Placeholder.component("mode", mode))));
        lines.add(AdminTuiBuilder.divider());
        lines.add(AdminTuiBuilder.line(text.regenTitle.asComponent()));

        lines.add(setting(text, text.labelGenerationThreads, config.regen.threadPool.parallelism,
            recommendation.generationThreads(), text.hoverGenerationThreads, resources));
        lines.add(setting(text, text.labelGenerationPriority, config.regen.threadPool.priority,
            recommendation.generationPriority(), text.hoverGenerationPriority, resources));
        lines.add(setting(text, text.labelBatchDelay, config.regen.batchDelayTicks,
            recommendation.batchDelayTicks(), text.hoverBatchDelay, resources));
        lines.add(setting(text, text.labelBatchConcurrency, config.regen.batchConcurrency,
            recommendation.batchConcurrency(), text.hoverBatchConcurrency, resources));
        lines.add(setting(text, text.labelBatchSize, config.regen.maxChunksPerBatch,
            recommendation.maxChunksPerBatch(), text.hoverBatchSize, resources));
        lines.add(setting(text, text.labelWorkTileSize, config.regen.workTileSize,
            recommendation.workTileSize(), text.hoverWorkTileSize, resources));
        lines.add(setting(text, text.labelApplyBatchSize, config.regen.applyBatchSize,
            recommendation.applyBatchSize(), text.hoverApplyBatchSize, resources));
        lines.add(setting(text, text.labelMemorySafety, config.regen.memorySafety.enabled,
            true, text.hoverMemorySafety, resources));
        lines.add(setting(text, text.labelActiveBatches, config.regen.memorySafety.maxActiveBatches,
            recommendation.maxActiveBatches(), text.hoverActiveBatches, resources));
        lines.add(setting(text, text.labelMemoryBatchSize, config.regen.memorySafety.maxChunksPerBatch,
            recommendation.memorySafeMaxChunksPerBatch(), text.hoverMemoryBatchSize, resources));
        lines.add(setting(text, text.labelGenerationLimit, config.regen.memorySafety.maxGenerationThreads,
            recommendation.maxGenerationThreads(), text.hoverGenerationLimit, resources));
        lines.add(setting(text, text.labelHeapWatermark, config.regen.memorySafety.heapHighWatermarkPercent,
            recommendation.heapHighWatermarkPercent(), text.hoverHeapWatermark, resources));

        lines.add(AdminTuiBuilder.divider());
        lines.add(AdminTuiBuilder.line(text.scanTitle.asComponent()));
        lines.add(setting(text, text.labelScanThreads, config.scan.threadPool.parallelism,
            recommendation.scanThreads(), text.hoverScanThreads, resources));
        lines.add(setting(text, text.labelScanPriority, config.scan.threadPool.priority,
            recommendation.scanPriority(), text.hoverScanPriority, resources));
        lines.add(AdminTuiBuilder.divider());
        lines.add(AdminTuiBuilder.actionBar(AdminTuiBuilder.row(
            AdminTuiBuilder.normalButton(text.buttonBack.asComponent(), ClickEvent.runCommand("/cr tune"), text.hoverBack.asComponent()),
            AdminTuiBuilder.actionButton(text.buttonRefresh.asComponent(),
                ClickEvent.runCommand("/cr tune preview " + recommendation.profile().commandName()), text.hoverRefresh.asComponent()),
            AdminTuiBuilder.severeButton(
                text.buttonApply.withPlaceholders(Placeholder.component("mode", mode)),
                ClickEvent.runCommand("/cr tune apply " + recommendation.profile().commandName()),
                text.hoverApply.asComponent())
        )));
        return lines;
    }

    public static Component profileLabel(Messages.Tuning text, TuningProfile profile) {
        return switch (profile) {
            case OPTIMIZED -> text.modeOptimized.asComponent();
            case BALANCED -> text.modeBalanced.asComponent();
            case PERFORMANCE -> text.modePerformance.asComponent();
        };
    }

    public static Component profileHover(Messages.Tuning text, TuningProfile profile) {
        return switch (profile) {
            case OPTIMIZED -> text.hoverModeOptimized.asComponent();
            case BALANCED -> text.hoverModeBalanced.asComponent();
            case PERFORMANCE -> text.hoverModePerformance.asComponent();
        };
    }

    private static Component modeButton(Messages.Tuning text, TuningProfile profile) {
        return AdminTuiBuilder.actionButton(profileLabel(text, profile),
            ClickEvent.runCommand("/cr tune preview " + profile.commandName()), profileHover(text, profile));
    }

    private static Component info(Messages.Tuning text, String label, String value, Component hover) {
        Component line = text.infoLine.withPlaceholders(
            Placeholder.unparsed("label", label), Placeholder.unparsed("value", value));
        return AdminTuiBuilder.line(line.hoverEvent(HoverEvent.showText(hover)));
    }

    private static Component setting(Messages.Tuning text, String label, Object current, Object recommended,
                                     github.freshchromatic.freshlib.config.Messages.ComponentMessage hover,
                                     ServerResourceSnapshot resources) {
        String currentValue = String.valueOf(current);
        String recommendedValue = String.valueOf(recommended);
        Component line = text.settingLine.withPlaceholders(
            Placeholder.unparsed("label", label),
            Placeholder.unparsed("current", currentValue),
            Placeholder.unparsed("recommended", recommendedValue));
        Component hoverText = hover.withPlaceholders(
            Placeholder.unparsed("current", currentValue),
            Placeholder.unparsed("recommended", recommendedValue),
            Placeholder.unparsed("processors", Integer.toString(resources.logicalProcessors())),
            Placeholder.unparsed("heap", Long.toString(resources.maxHeapMiB())));
        return AdminTuiBuilder.line(line.hoverEvent(HoverEvent.showText(hoverText)));
    }

    private static String replace(String template, String key1, Object value1, String key2, Object value2) {
        return template.replace("<" + key1 + ">", String.valueOf(value1))
            .replace("<" + key2 + ">", String.valueOf(value2));
    }

    private static String replace(String template, String key, Object value) {
        return template.replace("<" + key + ">", String.valueOf(value));
    }
}

package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.presentation.command.ConfirmationManager;
import github.freshchromatic.chunkrevive.presentation.display.AdminTuiBuilder;
import github.freshchromatic.chunkrevive.presentation.display.TuningTuiBuilder;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.scanning.DiskChunkScanner;
import github.freshchromatic.chunkrevive.feature.tuning.TuningCalculator;
import github.freshchromatic.chunkrevive.feature.tuning.TuningProfile;
import github.freshchromatic.chunkrevive.feature.tuning.TuningService;
import github.freshchromatic.freshlib.command.Commander;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.incendo.cloud.context.CommandContext;

/** Handles the complete {@code /cr tune} workflow. */
public final class TuningCommands {
    private final ChunkRevivePlugin plugin;
    private final MarkRegistry markRegistry;
    private final DiskChunkScanner scanner;
    private final ConfirmationManager confirmations;
    private final TuningService tuningService;
    private Messages messages;

    public TuningCommands(
            ChunkRevivePlugin plugin,
            MarkRegistry markRegistry,
            DiskChunkScanner scanner,
            ConfirmationManager confirmations,
            Messages messages) {
        this.plugin = plugin;
        this.markRegistry = markRegistry;
        this.scanner = scanner;
        this.confirmations = confirmations;
        this.messages = messages;
        this.tuningService = new TuningService(plugin);
    }

    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    public void overview(CommandContext<Commander> context) {
        var resources = tuningService.captureResources();
        var recommended = TuningCalculator.recommendedProfile(resources);
        TuningTuiBuilder.overview(messages.tuning, resources, recommended)
            .forEach(context.sender()::sendMessage);
    }

    public void preview(CommandContext<Commander> context) {
        TuningProfile profile = resolveProfile(context);
        if (profile == null) return;
        var resources = tuningService.captureResources();
        var recommendation = tuningService.calculate(resources, profile);
        TuningTuiBuilder.preview(messages.tuning, plugin.getPluginConfig(), resources, recommendation)
            .forEach(context.sender()::sendMessage);
    }

    public void apply(CommandContext<Commander> context, boolean confirmed) {
        var sender = context.sender();
        TuningProfile profile = resolveProfile(context);
        if (profile == null) return;

        if (markRegistry.getRegenerationQueue().isRunning() || scanner.isRunning()) {
            sender.sendMessage(messages.tuning.busy.asComponent());
            return;
        }

        String confirmationKey = "tune:" + profile.commandName();
        if (!confirmed) {
            int timeout = Math.max(1, plugin.getPluginConfig().safety.confirmTimeoutSeconds);
            confirmations.request(sender.commanderId(), confirmationKey, timeout);
            Component mode = TuningTuiBuilder.profileLabel(messages.tuning, profile);
            sender.sendMessage(AdminTuiBuilder.line(messages.tuning.confirmRequired.withPlaceholders(
                Placeholder.component("mode", mode),
                Placeholder.unparsed("timeout", Integer.toString(timeout)))));
            sender.sendMessage(AdminTuiBuilder.actionBar(AdminTuiBuilder.row(
                AdminTuiBuilder.normalButton(messages.tuning.buttonCancel.asComponent(),
                    ClickEvent.runCommand("/cr tune preview " + profile.commandName()), messages.tuning.hoverCancel.asComponent()),
                AdminTuiBuilder.severeButton(messages.tuning.buttonConfirm.asComponent(),
                    ClickEvent.runCommand("/cr tune apply " + profile.commandName() + " --confirm"),
                    messages.tuning.hoverConfirm.asComponent())
            )));
            return;
        }

        if (!confirmations.confirm(sender.commanderId(), confirmationKey)) {
            sender.sendMessage(messages.tuning.confirmExpired.asComponent());
            return;
        }

        var recommendation = tuningService.calculate(tuningService.captureResources(), profile);
        if (!tuningService.apply(recommendation)) {
            sender.sendMessage(messages.tuning.saveFailed.asComponent());
            return;
        }
        sender.sendMessage(messages.tuning.applied.withPlaceholders(
            Placeholder.component("mode", TuningTuiBuilder.profileLabel(messages.tuning, profile))));
    }

    private TuningProfile resolveProfile(CommandContext<Commander> context) {
        String raw = context.get("profile");
        var profile = TuningProfile.parse(raw);
        if (profile.isPresent()) return profile.get();
        context.sender().sendMessage(messages.tuning.unknownProfile.withPlaceholders(
            Placeholder.unparsed("profile", raw)));
        return null;
    }
}

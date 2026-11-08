package github.freshchromatic.freshlib.command.commands;

import com.google.inject.Inject;
import org.incendo.cloud.context.CommandContext;
import org.bukkit.plugin.java.JavaPlugin;

import static org.incendo.cloud.minecraft.extras.RichDescription.richDescription;

import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.FreshLibCommand;
import github.freshchromatic.freshlib.command.FreshLibCommands;
import github.freshchromatic.freshlib.config.ConfigManager;
import github.freshchromatic.freshlib.util.Components;

public final class ReloadCommand extends FreshLibCommand {

    private final JavaPlugin    plugin;
    private final ConfigManager configManager;

    @Inject
    ReloadCommand(final FreshLibCommands commands, final JavaPlugin plugin, final ConfigManager configManager) {
        super(commands);
        this.plugin = plugin;
        this.configManager = configManager;
    }

    @Override
    public void register() {
        this.commands.registerSubcommand(builder ->
            builder.literal("reload")
                .commandDescription(richDescription(configManager.messages().command.description.reload))
                .permission("freshlib.command.reload")
                .handler(this::execute));
    }

    void execute(final CommandContext<Commander> context) {
        if (!this.configManager.tryReload()) {
            context.sender().sendMessage(configManager.messages().pluginReloadFailed.withPlaceholders(
                Components.placeholder("name", this.plugin.getName())));
            return;
        }

        context.sender().sendMessage(configManager.messages().pluginReloaded.withPlaceholders(Components.placeholder(
            "name", this.plugin.getName()), Components.placeholder("version",
                this.plugin.getPluginMeta().getVersion())));
    }
}

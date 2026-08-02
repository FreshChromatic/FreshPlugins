package github.freshchromatic.freshlib.command.commands;

import com.google.inject.Inject;
import org.incendo.cloud.context.CommandContext;
import org.bukkit.plugin.java.JavaPlugin;

import static org.incendo.cloud.minecraft.extras.RichDescription.richDescription;

import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.util.Components;

public final class ReloadCommand extends github.freshchromatic.freshlib.command.FreshLibCommand {
    private final JavaPlugin plugin;
    private final github.freshchromatic.freshlib.config.ConfigManager configManager;

    @Inject
    private ReloadCommand(
        final github.freshchromatic.freshlib.command.FreshLibCommands commands,
        final JavaPlugin plugin,
        final github.freshchromatic.freshlib.config.ConfigManager configManager
    ) {
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

    private void execute(final CommandContext<Commander> context) {
        this.configManager.reload();

        context.sender().sendMessage(
            configManager.messages().pluginReloaded.withPlaceholders(
                Components.placeholder("name", this.plugin.getName()),
                Components.placeholder("version", this.plugin.getPluginMeta().getVersion())
            )
        );
    }
}

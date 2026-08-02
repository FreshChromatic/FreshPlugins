package github.freshchromatic.freshlib.command;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import io.leangen.geantyref.TypeToken;
import java.util.List;
import java.util.function.Function;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.key.CloudKey;
import org.incendo.cloud.paper.PaperCommandManager;
import github.freshchromatic.freshlib.command.commands.HelpCommand;
import github.freshchromatic.freshlib.command.commands.ReloadCommand;
import github.freshchromatic.freshlib.config.ConfigManager;
import github.freshchromatic.freshlib.config.Messages;

@SuppressWarnings("UnstableApiUsage")
@Singleton
public final class FreshLibCommands {
    public static final CloudKey<Messages> MESSAGES_KEY = CloudKey.of(
        "freshlib-messages", TypeToken.get(Messages.class));

    private final Injector injector;
    private final ConfigManager configManager;
    private final CommandManager<Commander> commandManager;

    @Inject
    private FreshLibCommands(
        final Injector injector,
        final JavaPlugin plugin,
        final ExceptionHandler exceptionHandler,
        final ConfigManager configManager
    ) {
        this.injector = injector;
        this.configManager = configManager;

        final SenderMapper<io.papermc.paper.command.brigadier.CommandSourceStack, Commander> senderMapper =
            SenderMapper.create(
                FreshLibCommander::from,
                commander -> ((FreshLibCommander) commander).stack()
            );

        this.commandManager = PaperCommandManager.builder(senderMapper)
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin);

        exceptionHandler.registerExceptionHandlers(this.commandManager);
    }

    public void registerCommands() {
        final List<Class<? extends FreshLibCommand>> commands = List.of(
            HelpCommand.class,
            ReloadCommand.class
        );

        for (final Class<? extends FreshLibCommand> command : commands) {
            this.injector.getInstance(command).register();
        }
    }

    public void register(final Command.Builder<? extends Commander> builder) {
        this.commandManager.command(builder);
    }

    public void registerSubcommand(
        final Function<Command.Builder<Commander>, Command.Builder<? extends Commander>> builderModifier
    ) {
        this.register(builderModifier.apply(this.rootBuilder()));
    }

    public Command.Builder<Commander> rootBuilder() {
        github.freshchromatic.freshlib.config.FreshLibConfig config = configManager.config();
        return this.commandManager.commandBuilder(
            config.settings.commands.mainCommandLabel,
            Description.of(String.format("FreshLib command. '/%s help'", config.settings.commands.mainCommandLabel)),
            config.settings.commands.mainCommandAliases.toArray(String[]::new)
        );
    }

    public CommandManager<Commander> commandManager() {
        return this.commandManager;
    }

    private static <T> CloudKey<T> createTypeKey(final Class<T> type) {
        return CloudKey.of("freshlib-" + type.getName(), TypeToken.get(type));
    }
}

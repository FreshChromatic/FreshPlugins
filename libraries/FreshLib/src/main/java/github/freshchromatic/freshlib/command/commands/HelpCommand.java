package github.freshchromatic.freshlib.command.commands;

import com.google.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.component.DefaultValue;
import org.incendo.cloud.component.TypedCommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.help.result.CommandEntry;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.config.ConfigManager;
import github.freshchromatic.freshlib.config.Messages;
import github.freshchromatic.freshlib.util.Components;

import static org.incendo.cloud.minecraft.extras.MinecraftHelp.helpColors;
import static org.incendo.cloud.minecraft.extras.RichDescription.richDescription;

public final class HelpCommand extends github.freshchromatic.freshlib.command.FreshLibCommand {
    private final MinecraftHelp<Commander> minecraftHelp;
    private final TypedCommandComponent<Commander, String> helpQueryArgument;
    private final ConfigManager configManager;

    @Inject
    private HelpCommand(final github.freshchromatic.freshlib.command.FreshLibCommands commands, final ConfigManager configManager) {
        super(commands);
        this.configManager = configManager;
        this.minecraftHelp = createMinecraftHelp(commands.commandManager(), configManager);
        this.helpQueryArgument = createHelpQueryArgument(commands, configManager);
    }

    @Override
    public void register() {
        this.commands.registerSubcommand(builder ->
            builder.literal("help")
                .commandDescription(richDescription(configManager.messages().command.description.help))
                .argument(this.helpQueryArgument)
                .permission("freshlib.command.help")
                .handler(this::executeHelp));
    }

    private void executeHelp(final CommandContext<Commander> context) {
        this.minecraftHelp.queryCommands(context.get(this.helpQueryArgument), context.sender());
    }

    private static TypedCommandComponent<Commander, String> createHelpQueryArgument(
            final github.freshchromatic.freshlib.command.FreshLibCommands commands,
            final ConfigManager configManager
    ) {
        final var commandHelpHandler = commands.commandManager().createHelpHandler();
        final BlockingSuggestionProvider.Strings<Commander> suggestions = (context, input) ->
            commandHelpHandler.queryRootIndex(context.sender()).entries().stream()
                .map(CommandEntry::syntax)
                .toList();
        return CommandComponent.<Commander, String>ofType(String.class, "query")
            .parser(StringParser.greedyStringParser())
            .suggestionProvider(suggestions)
            .optional()
            .defaultValue(DefaultValue.constant(""))
            .description(richDescription(configManager.messages().command.argument.helpQuery))
            .build();
    }

    private static MinecraftHelp<Commander> createMinecraftHelp(
            final CommandManager<Commander> manager,
            final ConfigManager configManager
    ) {
        return MinecraftHelp.<Commander>builder()
            .commandManager(manager)
            .audienceProvider(AudienceProvider.nativeAudience())
            .commandPrefix(String.format("/%s help", configManager.config().settings.commands.mainCommandLabel))
            .colors(helpColors(
                TextColor.fromHexString("#0044AA"),
                NamedTextColor.WHITE,
                TextColor.fromHexString("#0099FF"),
                NamedTextColor.GRAY,
                NamedTextColor.DARK_GRAY
            ))
            .messageProvider((sender, key, args) -> helpMessage(configManager, key, args))
            .build();
    }

    private static Component helpMessage(final ConfigManager configManager, final String key, final Map<String, String> args) {
        Messages.ComponentMessage message = configManager.messages().command.message.help.getMessage(key);
        if (message == null) {
            return Component.text("Missing help key: " + key, NamedTextColor.RED);
        }
        return message.withPlaceholders(args.entrySet().stream()
                .map(e -> Components.placeholder(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));
    }
}

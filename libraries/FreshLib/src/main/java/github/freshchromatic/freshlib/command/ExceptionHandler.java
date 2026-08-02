package github.freshchromatic.freshlib.command;

import com.google.inject.Inject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.util.ComponentMessageThrowable;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.exception.CommandExecutionException;
import org.incendo.cloud.exception.InvalidCommandSenderException;
import org.incendo.cloud.exception.InvalidSyntaxException;
import org.incendo.cloud.exception.NoPermissionException;
import org.incendo.cloud.exception.handling.ExceptionContext;
import org.incendo.cloud.exception.parsing.ParserException;
import org.incendo.cloud.util.TypeUtils;
import org.spongepowered.configurate.util.NamingSchemes;
import github.freshchromatic.freshlib.command.exception.CommandCompleted;
import github.freshchromatic.freshlib.config.ConfigManager;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;

import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
import static net.kyori.adventure.text.event.ClickEvent.copyToClipboard;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.WHITE;
import static net.kyori.adventure.text.format.TextDecoration.ITALIC;
import static org.incendo.cloud.exception.handling.ExceptionHandler.unwrappingHandler;

public final class ExceptionHandler {
    private final ConfigManager configManager;

    @Inject
    private ExceptionHandler(final ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void registerExceptionHandlers(final CommandManager<Commander> manager) {
        manager.exceptionController()
            .registerHandler(CommandExecutionException.class, this::commandExecution)
            .registerHandler(CommandExecutionException.class, unwrappingHandler(CommandCompleted.class))
            .registerHandler(CommandCompleted.class, this::commandCompleted)
            .registerHandler(NoPermissionException.class, this::noPermission)
            .registerHandler(ArgumentParseException.class, this::argumentParsing)
            .registerHandler(InvalidCommandSenderException.class, this::invalidSender)
            .registerHandler(InvalidSyntaxException.class, this::invalidSyntax);
    }

    private void commandCompleted(final ExceptionContext<Commander, CommandCompleted> ctx) {
        final Component message = ctx.exception().componentMessage();
        if (message != null) {
            decorateAndSend(ctx.context().sender(), message);
        }
    }

    private void commandExecution(final ExceptionContext<Commander, CommandExecutionException> ctx) {
        final Throwable cause = ctx.exception().getCause();
        Logging.logger().severe("An unexpected error occurred during command execution: " + cause);

        final TextComponent.Builder message = text();
        message.append(configManager.messages().command.message.exception.commandExecution);
        if (ctx.context().sender().hasPermission("freshlib.command-exception-stacktrace")) {
            decorateWithHoverStacktrace(message, cause);
        }
        decorateAndSend(ctx.context().sender(), message);
    }

    private void noPermission(final ExceptionContext<Commander, NoPermissionException> ctx) {
        decorateAndSend(ctx.context().sender(), configManager.messages().command.message.exception.noPermission);
    }

    private void argumentParsing(final ExceptionContext<Commander, ArgumentParseException> ctx) {
        final Throwable cause = ctx.exception().getCause();
        final Supplier<Component> fallback = () -> Objects.requireNonNull(ComponentMessageThrowable.getOrConvertMessage(cause));
        final Component message;
        if (cause instanceof final ParserException parserException) {
            final TagResolver[] placeholders = Arrays.stream(parserException.captionVariables())
                .map(variable -> Components.placeholder(NamingSchemes.SNAKE_CASE.coerce(variable.key()), variable.value()))
                .toArray(TagResolver[]::new);
            final String key = parserException.errorCaption().key().replace("argument.parse.failure.", "");
            Component fromConfig;
            try {
                fromConfig = configManager.messages().command.message.parserException.getMessage(key).withPlaceholders(placeholders);
            } catch (final Exception ex) {
                fromConfig = null;
            }
            message = fromConfig != null ? fromConfig : fallback.get();
        } else {
            message = fallback.get();
        }
        final Component finalMessage = message;
        decorateAndSend(
            ctx.context().sender(),
            configManager.messages().command.message.exception.invalidArgument.withPlaceholders(Components.placeholder("message", finalMessage))
        );
    }

    private void invalidSender(final ExceptionContext<Commander, InvalidCommandSenderException> ctx) {
        final Component requiredSender = Component.join(
            JoinConfiguration.separator(text(" or ")),
            ctx.exception().requiredSenderTypes().stream()
                .map(TypeUtils::simpleName)
                .map(Component::text)
                .toList()
        );
        final Component message = configManager.messages().command.message.exception.invalidSenderType.withPlaceholders(
            Components.placeholder("required_sender_type", requiredSender)
        );
        decorateAndSend(ctx.context().sender(), message);
    }

    private void invalidSyntax(final ExceptionContext<Commander, InvalidSyntaxException> ctx) {
        final Component message = configManager.messages().command.message.exception.invalidSyntax.withPlaceholders(
            Components.placeholder("correct_syntax",
                Components.highlightSpecialCharacters(text("/%s".formatted(ctx.exception().correctSyntax())), WHITE))
        );
        decorateAndSend(ctx.context().sender(), message);
    }

    private void decorateAndSend(final Audience audience, final ComponentLike componentLike) {
        final Component message = textOfChildren(
            configManager.messages().command.prefix.asComponent()
                .hoverEvent(configManager.messages().clickForHelp.asComponent())
                .clickEvent(runCommand("/%s help".formatted(configManager.config().settings.commands.mainCommandLabel))),
            componentLike
        );
        audience.sendMessage(message);
    }

    private void decorateWithHoverStacktrace(final TextComponent.Builder message, final Throwable cause) {
        final StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        final String stackTrace = writer.toString().replaceAll("\t", "    ");
        final TextComponent.Builder hoverText = text();
        final Component throwableMessage = ComponentMessageThrowable.getOrConvertMessage(cause);
        if (throwableMessage != null) {
            hoverText.append(throwableMessage)
                .append(newline())
                .append(newline());
        }
        hoverText.append(text(stackTrace))
            .append(newline())
            .append(text("    "))
            .append(configManager.messages().clickToCopy.asComponent().color(GRAY).decorate(ITALIC));

        message.hoverEvent(hoverText.build());
        message.clickEvent(copyToClipboard(stackTrace));
    }
}

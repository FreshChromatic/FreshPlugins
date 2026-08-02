package github.freshchromatic.freshlib.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import github.freshchromatic.freshlib.util.Components;
import github.freshchromatic.freshlib.util.Logging;

import java.util.stream.StreamSupport;

@ConfigSerializable
public final class Messages implements Config {

    public Command command = new Command();
    
    @Setting("click-to-copy")
    public ComponentMessage clickToCopy = new ComponentMessage("Click to copy to clipboard");
    
    @Setting("click-for-help")
    public ComponentMessage clickForHelp = new ComponentMessage("Click for help");

    @Setting("plugin-reloaded")
    public ComponentMessage pluginReloaded = new ComponentMessage("<green><name> v<version> reloaded");

    @ConfigSerializable
    public static class Command {
        public ComponentMessage prefix = new ComponentMessage("<white>[<gradient:#0099FF:#0044AA>FreshLib</gradient>]</white> ");
        public Description description = new Description();
        public Argument argument = new Argument();
        public CommandMessages message = new CommandMessages();
    }

    @ConfigSerializable
    public static class Description {
        public ComponentMessage help = new ComponentMessage("Get help for FreshLib commands");
        public ComponentMessage reload = new ComponentMessage("Reload the FreshLib configuration");
    }

    @ConfigSerializable
    public static class Argument {
        @Setting("help-query")
        public ComponentMessage helpQuery = new ComponentMessage("Help Query");
    }

    @ConfigSerializable
    public static class CommandMessages {
        public Help help = new Help();
        public Exception exception = new Exception();
        @Setting("parser-exception")
        public ParserException parserException = new ParserException();
    }

    @ConfigSerializable
    public static class Help {
        public ComponentMessage title = new ComponentMessage("FreshLib command help");
        public ComponentMessage command = new ComponentMessage("Command");
        public ComponentMessage description = new ComponentMessage("Description");
        @Setting("no-description")
        public ComponentMessage noDescription = new ComponentMessage("No description");
        public ComponentMessage arguments = new ComponentMessage("Arguments");
        public ComponentMessage optional = new ComponentMessage("Optional");
        @Setting("showing-results-for-query")
        public ComponentMessage showingResultsForQuery = new ComponentMessage("Showing search results for query");
        @Setting("no-results-for-query")
        public ComponentMessage noResultsForQuery = new ComponentMessage("No results for query");
        @Setting("available-commands")
        public ComponentMessage availableCommands = new ComponentMessage("Available Commands");
        @Setting("click-to-show-help")
        public ComponentMessage clickToShowHelp = new ComponentMessage("Click to show help for this command");
        @Setting("page-out-of-range")
        public ComponentMessage pageOutOfRange = new ComponentMessage("Error: Page <page> is not in range. Must be in range [1, <max_pages>]");
        @Setting("click-for-next-page")
        public ComponentMessage clickForNextPage = new ComponentMessage("Click for next page");
        @Setting("click-for-previous-page")
        public ComponentMessage clickForPreviousPage = new ComponentMessage("Click for previous page");

        public ComponentMessage getMessage(String key) {
            return switch (key) {
                case "title" -> title;
                case "command" -> command;
                case "description" -> description;
                case "no-description" -> noDescription;
                case "arguments" -> arguments;
                case "optional" -> optional;
                case "showing-results-for-query" -> showingResultsForQuery;
                case "no-results-for-query" -> noResultsForQuery;
                case "available-commands" -> availableCommands;
                case "click-to-show-help" -> clickToShowHelp;
                case "page-out-of-range" -> pageOutOfRange;
                case "click-for-next-page" -> clickForNextPage;
                case "click-for-previous-page" -> clickForPreviousPage;
                default -> null;
            };
        }
    }

    @ConfigSerializable
    public static class Exception {
        @Setting("command-execution")
        public ComponentMessage commandExecution = new ComponentMessage("<red>An internal error occurred while attempting to perform this command.");
        @Setting("no-permission")
        public ComponentMessage noPermission = new ComponentMessage("<red>I'm sorry, but you do not have permission to perform this command.\n"
                + "Please contact the server administrators if you believe that this is in error.");
        @Setting("invalid-argument")
        public ComponentMessage invalidArgument = new ComponentMessage("<red>Invalid command argument<white>:</white> <gray><message>");
        @Setting("invalid-sender-type")
        public ComponentMessage invalidSenderType = new ComponentMessage("<red>Invalid command sender type. You must be of type <gray><required_sender_type></gray>.");
        @Setting("invalid-syntax")
        public ComponentMessage invalidSyntax = new ComponentMessage("<red>Invalid command syntax. Correct command syntax is<white>:</white> <gray><correct_syntax>");
    }

    @ConfigSerializable
    public static class ParserException {
        public ComponentMessage integer = new ComponentMessage("Invalid integer: <input>");
        public ComponentMessage number = new ComponentMessage("Invalid number: <input>");
        @Setting("boolean")
        public ComponentMessage boolean_ = new ComponentMessage("Invalid boolean: <input>");
        @Setting("enum")
        public ComponentMessage enum_ = new ComponentMessage("Invalid choice: <input>. Valid choices: <choices>");
        public ComponentMessage player = new ComponentMessage("No player found for: <input>");
        public ComponentMessage world = new ComponentMessage("No world found for: <input>");
        public ComponentMessage string = new ComponentMessage("'<input>' is not a valid string of type <string_mode>");
        public ComponentMessage progress = new ComponentMessage("Progress: <progress>");

        public ComponentMessage getMessage(String key) {
            return switch (key) {
                case "integer" -> integer;
                case "number" -> number;
                case "boolean" -> boolean_;
                case "enum" -> enum_;
                case "player" -> player;
                case "world" -> world;
                case "string" -> string;
                case "progress" -> progress;
                default -> null;
            };
        }
    }

    // -------------------------------------------------------------------------
    // Message Types
    // -------------------------------------------------------------------------

    public sealed interface Message permits StringMessage, ComponentMessage {
    }

    public record StringMessage(String message) implements Message {
        public String withPlaceholders(final Object... keyValuePairs) {
            return Logging.replace(this.message, keyValuePairs);
        }
    }

    public static final class ComponentMessage implements Message, ComponentLike {
        private final String miniMessage;
        private transient volatile Component noPlaceholders;

        public ComponentMessage(final String miniMessage) {
            this.miniMessage = miniMessage;
        }

        public Component withPlaceholders(final TagResolver... placeholders) {
            if (placeholders.length == 0) {
                return this.asComponent();
            }
            return Components.miniMessage(this.miniMessage, placeholders);
        }

        public Component withPlaceholders(final Iterable<TagResolver> placeholders) {
            return Components.miniMessage(this.miniMessage,
                    StreamSupport.stream(placeholders.spliterator(), false).toArray(TagResolver[]::new));
        }

        @Override
        public Component asComponent() {
            if (this.noPlaceholders == null) {
                synchronized (this) {
                    if (this.noPlaceholders == null) {
                        this.noPlaceholders = Components.miniMessage(this.miniMessage);
                    }
                }
            }
            return this.noPlaceholders;
        }

        public String miniMessage() {
            return this.miniMessage;
        }
    }
}

package github.freshchromatic.freshlib.command.exception;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.util.ComponentMessageThrowable;

public final class CommandCompleted extends RuntimeException implements ComponentMessageThrowable {
    private static final long serialVersionUID = -8318440562349647391L;

    private final Component message;

    private CommandCompleted(final Component message) {
        this.message = message;
    }

    public static CommandCompleted withoutMessage() {
        return new CommandCompleted(null);
    }

    public static CommandCompleted withMessage(final ComponentLike message) {
        return new CommandCompleted(message.asComponent());
    }

    @Override
    public Component componentMessage() {
        return this.message;
    }

    @Override
    public String getMessage() {
        return PlainTextComponentSerializer.plainText().serializeOr(this.message, "No message.");
    }
}

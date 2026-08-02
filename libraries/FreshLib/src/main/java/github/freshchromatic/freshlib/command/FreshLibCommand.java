package github.freshchromatic.freshlib.command;

public abstract class FreshLibCommand {
    protected final FreshLibCommands commands;

    protected FreshLibCommand(final FreshLibCommands commands) {
        this.commands = commands;
    }

    public abstract void register();
}

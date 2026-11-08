package github.freshchromatic.freshlib.command.commands;

import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.command.FreshLibCommands;
import github.freshchromatic.freshlib.config.ConfigManager;
import github.freshchromatic.freshlib.config.Messages;
import github.freshchromatic.freshlib.util.Components;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReloadCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsFailureWhenReloadFails() {
        var plugin = mock(JavaPlugin.class);
        var manager = mock(ConfigManager.class);
        var messages = new Messages();
        var sender = mock(Commander.class);
        CommandContext<Commander> context = mock(CommandContext.class);
        when(plugin.getName()).thenReturn("FreshLib");
        when(manager.messages()).thenReturn(messages);
        when(manager.tryReload()).thenReturn(false);
        when(context.sender()).thenReturn(sender);
        var command = new ReloadCommand(mock(FreshLibCommands.class), plugin, manager);

        command.execute(context);

        verify(sender).sendMessage(messages.pluginReloadFailed.withPlaceholders(Components.placeholder("name",
            "FreshLib")));
    }
}

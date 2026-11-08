package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.bootstrap.ChunkRevivePlugin;
import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.freshlib.command.Commander;
import github.freshchromatic.freshlib.scheduler.Scheduler;
import org.incendo.cloud.context.CommandContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCommandsReloadTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsReloadFailure() {
        var plugin = mock(ChunkRevivePlugin.class);
        var messages = new Messages();
        var commands = new AdminCommands(plugin, null, null, null, messages);
        CommandContext<Commander> context = mock(CommandContext.class);
        var sender = mock(Commander.class);
        when(context.sender()).thenReturn(sender);
        when(plugin.tryReload()).thenReturn(false);

        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            scheduler.when(Scheduler::isGlobalTickThread).thenReturn(true);
            commands.reload(context);
        }

        verify(sender).sendMessage(messages.command.reloadFailed.asComponent());
    }
}

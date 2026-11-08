package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.config.Messages;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginRuntimeReloadTest {

    @Test
    @SuppressWarnings("unchecked")
    void failedConfigLoadDoesNotApplyEitherConfiguration() {
        ConfigurateConfigManager<PluginConfig> configs = mock(ConfigurateConfigManager.class);
        ConfigurateConfigManager<Messages> messages = mock(ConfigurateConfigManager.class);
        when(configs.config()).thenReturn(new PluginConfig());
        when(configs.loadCandidate()).thenReturn(Optional.empty());
        when(messages.loadCandidate()).thenReturn(Optional.of(new Messages()));
        var runtime = new PluginRuntime(mock(ChunkRevivePlugin.class), configs, messages, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

        assertFalse(runtime.reload());
        verify(configs, never()).applyLoaded(any());
        verify(messages, never()).applyLoaded(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedMessagesLoadDoesNotApplyEitherConfiguration() {
        ConfigurateConfigManager<PluginConfig> configs = mock(ConfigurateConfigManager.class);
        ConfigurateConfigManager<Messages> messages = mock(ConfigurateConfigManager.class);
        when(configs.config()).thenReturn(new PluginConfig());
        when(configs.loadCandidate()).thenReturn(Optional.of(new PluginConfig()));
        when(messages.loadCandidate()).thenReturn(Optional.empty());
        var runtime = new PluginRuntime(mock(ChunkRevivePlugin.class), configs, messages, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

        assertFalse(runtime.reload());
        verify(configs, never()).applyLoaded(any());
        verify(messages, never()).applyLoaded(any());
    }
}

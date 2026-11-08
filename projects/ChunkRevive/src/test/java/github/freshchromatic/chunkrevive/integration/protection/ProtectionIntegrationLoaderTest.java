package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ProtectionIntegrationLoaderTest {

    @Test
    void doesNotLoadOptionalIntegrationClassesWhenTheirPluginsAreAbsent() {
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.isPluginEnabled(anyString())).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            ProtectionIntegration protection = ProtectionIntegrationLoader.load(PluginConfig.LandProtection::new);

            assertFalse(protection.landProtection().isEnabled());
        }
    }
}

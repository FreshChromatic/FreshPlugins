package github.freshchromatic.chunkrevive.infrastructure.persistence;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DatabaseFactoryTest {

    @Test
    void rejectsUnknownDatabaseType() {
        var config = new PluginConfig.Database();
        config.type = "mysqll";

        assertThrows(IllegalArgumentException.class, () -> DatabaseFactory.create(mock(JavaPlugin.class), config));
    }
}

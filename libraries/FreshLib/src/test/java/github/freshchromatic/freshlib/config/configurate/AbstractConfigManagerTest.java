package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractConfigManagerTest {

    @Test
    void preservesEmptyScalarValuesWhenMergingDefaults(@TempDir Path directory) throws IOException {
        Path configFile = directory.resolve("config.yml");
        Files.writeString(configFile, "permission: ''\n");

        ConfigurateConfigManager<TestConfig> manager = new ConfigurateConfigManager<>(configFile, TestConfig.class);
        manager.load();

        assertEquals("", manager.config().permission);
    }

    @ConfigSerializable
    public static final class TestConfig implements Config {
        public String permission = "default.permission";
    }
}

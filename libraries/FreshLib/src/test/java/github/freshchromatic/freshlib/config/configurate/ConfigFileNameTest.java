package github.freshchromatic.freshlib.config.configurate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileNameTest {

    @Test
    void acceptsPortableSingleFileNames() {
        var fileName = ConfigFileName.parse("lang-zh_tw.yml").orElseThrow();

        assertEquals("lang-zh_tw.yml", fileName.value());
        assertTrue(ConfigFileName.parse("custom locale.yml").isPresent());
    }

    @Test
    void rejectsPathsAndPlatformSpecialFileNames() {
        assertFalse(ConfigFileName.parse("../outside.yml").isPresent());
        assertFalse(ConfigFileName.parse("locale/outside.yml").isPresent());
        assertFalse(ConfigFileName.parse("locale\\outside.yml").isPresent());
        assertFalse(ConfigFileName.parse("C:outside.yml").isPresent());
        assertFalse(ConfigFileName.parse(".").isPresent());
        assertFalse(ConfigFileName.parse(" ").isPresent());
        assertThrows(IllegalArgumentException.class, () -> new ConfigFileName("../outside.yml"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentConfigManager.resolveConfigPath(Path.of("locale"),
            "../outside.yml"));
    }
}

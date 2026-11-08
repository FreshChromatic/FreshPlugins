package github.freshchromatic.chunkrevive.config;

import github.freshchromatic.chunkrevive.config.PluginConfig.Structure;
import github.freshchromatic.chunkrevive.config.PluginConfig.Worlds;
import github.freshchromatic.chunkrevive.nms.BiomeMatchMode;
import github.freshchromatic.chunkrevive.nms.HeightmapKind;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginConfigParsingTest {

    @Test
    void nullEnumSettingsUseTheirDocumentedFallbacks(@TempDir Path directory) throws Exception {
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, """
            worlds:
              mode: null
            biome:
              match-mode: null
              heightmap-type: null
            structure:
              refresh:
                list-mode: null
            """);
        var manager = new ConfigurateConfigManager<>(configPath, PluginConfig.class);
        manager.load();
        PluginConfig config = manager.config();

        assertEquals(Structure.ListMode.BLACKLIST, config.structure.refresh.listModeEnum());

        config.worlds.mode = null;
        config.biome.matchMode = null;
        config.biome.heightmapType = null;
        config.structure.refresh.listMode = null;

        assertEquals(Worlds.ListMode.BLACKLIST, config.worlds.modeEnum());
        assertEquals(BiomeMatchMode.CENTER, config.biome.matchModeEnum());
        assertEquals(HeightmapKind.WORLD_SURFACE, config.biome.heightmapTypeEnum());
        assertEquals(Structure.ListMode.WHITELIST, config.structure.refresh.listModeEnum());
    }
}

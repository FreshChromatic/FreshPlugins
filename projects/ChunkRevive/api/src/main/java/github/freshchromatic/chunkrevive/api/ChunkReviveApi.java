package github.freshchromatic.chunkrevive.api;

import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import org.bukkit.plugin.Plugin;

/** Entry point registered through Bukkit's ServicesManager by ChunkRevive. */
public interface ChunkReviveApi {
    ApiVersion apiVersion();
    Capabilities capabilities();
    ChunkReviveClient client(Plugin consumer);
    IntegrationApi integrations();
    WorldGeneratorCompatibilityApi worldGenerators();
}

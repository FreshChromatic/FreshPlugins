package github.freshchromatic.freshlib.config.configurate.loader;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.AbstractConfigurationLoader;

import java.nio.file.Path;

/**
 * Provider interface for creating configuration loaders.
 * Allows support for multiple configuration formats (YAML, JSON, HOCON, etc.).
 *
 * <p>Based on DiscordSRV's ConfigLoaderProvider interface.</p>
 *
 * @param <T> the loader type
 */
public interface ConfigLoaderProvider<T extends AbstractConfigurationLoader<? extends CommentedConfigurationNode>> {

    /**
     * Creates a new configuration loader for the given path.
     *
     * @param path the configuration file path
     * @return a new configuration loader instance
     */
    T createLoader(Path path);

    /**
     * Returns the file extension this provider supports.
     *
     * @return file extension (e.g., ".yml", ".json")
     */
    String getSupportedExtension();

    /**
     * Returns the name of this format for logging purposes.
     *
     * @return format name
     */
    String getFormatName();
}

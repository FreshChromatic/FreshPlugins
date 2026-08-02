package github.freshchromatic.freshlib.config.configurate.loader;

import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;

/**
 * YAML configuration loader provider.
 *
 * <p>Based on DiscordSRV's YamlConfigLoaderProvider.</p>
 */
public class YamlConfigLoaderProvider implements ConfigLoaderProvider<YamlConfigurationLoader> {

    private final ConfigurationOptions options;
    private final NodeStyle nodeStyle;

    public YamlConfigLoaderProvider() {
        this(null, NodeStyle.BLOCK);
    }

    public YamlConfigLoaderProvider(ConfigurationOptions options) {
        this(options, NodeStyle.BLOCK);
    }

    public YamlConfigLoaderProvider(ConfigurationOptions options, NodeStyle nodeStyle) {
        this.options = options;
        this.nodeStyle = nodeStyle;
    }

    @Override
    public YamlConfigurationLoader createLoader(Path path) {
        YamlConfigurationLoader.Builder builder = YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(nodeStyle);

        if (options != null) {
            builder.defaultOptions(options);
        }

        return builder.build();
    }

    @Override
    public String getSupportedExtension() {
        return ".yml";
    }

    @Override
    public String getFormatName() {
        return "YAML";
    }
}

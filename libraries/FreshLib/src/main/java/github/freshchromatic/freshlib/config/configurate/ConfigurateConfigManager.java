package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;

import java.nio.file.Path;

public class ConfigurateConfigManager<T extends Config> extends AbstractConfigManager<T> {

    public ConfigurateConfigManager(Path path, Class<T> configClass) {
        super(path, configClass);
    }
}

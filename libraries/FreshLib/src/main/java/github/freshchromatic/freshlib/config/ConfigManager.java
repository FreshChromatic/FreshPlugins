package github.freshchromatic.freshlib.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import github.freshchromatic.freshlib.config.configurate.ConfigFileName;
import github.freshchromatic.freshlib.config.configurate.EnvironmentConfigManager;
import github.freshchromatic.freshlib.config.configurate.TranslatedConfigManager;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

@Singleton
public final class ConfigManager {

    private final Path                               dataDirectory;
    private ConfigurateConfigManager<FreshLibConfig> mainConfigManager;
    private TranslatedConfigManager<Messages>        messagesManager;

    @Inject
    public ConfigManager(final JavaPlugin plugin) {
        this.dataDirectory = plugin.getDataFolder().toPath();
        reload();
    }

    public void reload() {
        tryReload();
    }

    public boolean tryReload() {
        var nextConfig = new ConfigurateConfigManager<>(dataDirectory.resolve("config.yml"), FreshLibConfig.class);
        boolean configLoaded = nextConfig.tryLoad();
        if (!configLoaded && mainConfigManager != null) {
            return false;
        }

        var configuredFile = ConfigFileName.parse(nextConfig.config().settings.languageFile);
        if (configuredFile.isEmpty() && messagesManager != null) {
            return false;
        }
        if (configuredFile.isEmpty()) {
            Logging.logger().warning("Invalid language file name; using lang-en.yml for initial configuration");
        }
        ConfigFileName langFile = configuredFile.orElseGet(() -> new ConfigFileName("lang-en.yml"));
        Path langPath = EnvironmentConfigManager.resolveConfigPath(dataDirectory.resolve("locale"), langFile);
        var nextMessages = new TranslatedConfigManager<>(langPath, Messages.class, "/locale/" + langFile.value());
        boolean messagesLoaded = nextMessages.tryLoad();
        if (!messagesLoaded && messagesManager != null) {
            return false;
        }
        this.mainConfigManager = nextConfig;
        this.messagesManager = nextMessages;
        return configLoaded && messagesLoaded;
    }

    public FreshLibConfig config() {
        return mainConfigManager.config();
    }

    public Messages messages() {
        return messagesManager.config();
    }

    public void saveMainConfig() {
        mainConfigManager.save();
    }
}

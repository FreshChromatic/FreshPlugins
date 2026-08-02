package github.freshchromatic.freshlib.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import github.freshchromatic.freshlib.config.configurate.TranslatedConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

@Singleton
public final class ConfigManager {

    private final Path dataDirectory;
    private final ConfigurateConfigManager<FreshLibConfig> mainConfigManager;
    private TranslatedConfigManager<Messages> messagesManager;

    @Inject
    public ConfigManager(final JavaPlugin plugin) {
        this.dataDirectory = plugin.getDataFolder().toPath();
        this.mainConfigManager = new ConfigurateConfigManager<>(
                dataDirectory.resolve("config.yml"),
                FreshLibConfig.class);
        reload();
    }

    public void reload() {
        mainConfigManager.load();

        // Load messages based on the language file specified in main config
        String langFile = mainConfigManager.config().settings.languageFile;
        this.messagesManager = new TranslatedConfigManager<>(
                dataDirectory.resolve("locale").resolve(langFile),
                Messages.class,
                "/locale/" + langFile);
        this.messagesManager.load();
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

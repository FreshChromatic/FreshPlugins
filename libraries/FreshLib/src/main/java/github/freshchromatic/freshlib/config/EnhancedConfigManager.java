package github.freshchromatic.freshlib.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import github.freshchromatic.freshlib.config.configurate.ConfigDocGenerator;
import github.freshchromatic.freshlib.config.configurate.EnvironmentConfigManager;
import github.freshchromatic.freshlib.config.configurate.TranslatedConfigManager;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced configuration manager with full DiscordSRV feature parity.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Multi-environment support (dev/test/staging/prod)</li>
 * <li>Automatic file watching and hot-reload</li>
 * <li>Configuration validation with detailed error reporting</li>
 * <li>Backup mechanism for safe updates</li>
 * <li>Documentation generation</li>
 * <li>Caching layer for performance</li>
 * </ul>
 * </p>
 */
@Singleton
public final class EnhancedConfigManager {

    private final Path dataDirectory;
    private final JavaPlugin plugin;
    private github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader> mainConfigManager;
    private TranslatedConfigManager<Messages> messagesManager;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Inject
    public EnhancedConfigManager(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataDirectory = plugin.getDataFolder().toPath();
        initializeEnvironment();
        reload();
        enableAutoReload();
    }

    /**
     * Initializes environment from system properties or defaults to production.
     */
    private void initializeEnvironment() {
        String env = System.getProperty("FreshLib.environment");
        if (env == null) {
            env = System.getenv("FRESHLIB_ENVIRONMENT");
        }
        if (env != null) {
            EnvironmentConfigManager.setEnvironment(env);
            Logging.logger().info("Running in environment: " + env);
        }
    }

    public void reload() {
        try {
            Path configPath = EnvironmentConfigManager.resolveConfigPath(dataDirectory, "config.yml");

            mainConfigManager = new github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader>(
                    configPath,
                    FreshLibConfig.class) {
                @Override
                protected String header() {
                    return buildHeader();
                }

                @Override
                protected void validateSpecificFields(
                        github.freshchromatic.freshlib.config.configurate.validation.ValidationResult result) {
                    validateMainConfig(result);
                }
            };

            Path backupPath = dataDirectory.resolve("backups").resolve(
                    "config-" + System.currentTimeMillis() + ".yml.bak");

            mainConfigManager.load(true, true, backupPath);

            FreshLibConfig config = mainConfigManager.config();

            if (config.automaticConfigurationUpgrade) {
                Logging.logger().info("Automatic configuration upgrade is ENABLED");
                Logging.logger().info("Missing options will be automatically added with default values on next reload");
            } else {
                Logging.logger().info("Automatic configuration upgrade is DISABLED");
                Logging.logger().warning("New configuration options will NOT be automatically added!");
            }

            String langFile = config.settings.languageFile;
            Path langPath = EnvironmentConfigManager.resolveConfigPath(
                    dataDirectory.resolve("locale"),
                    langFile);

            messagesManager = new TranslatedConfigManager<>(
                    langPath,
                    Messages.class,
                    "/locale/" + langFile);
            messagesManager.load();

            initialized.set(true);
            Logging.logger().info("Configuration reloaded successfully");

        } catch (Exception e) {
            Logging.logger().severe("Failed to initialize configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String buildHeader() {
        StringBuilder header = new StringBuilder();
        header.append("FreshLib Configuration File\n");
        header.append("Version: ").append(plugin.getDescription().getVersion()).append("\n");
        header.append("Environment: ").append(EnvironmentConfigManager.getEnvironment().getId()).append("\n");
        header.append("\n");
        header.append("For documentation, see: https://github.com/freshchromatic/FreshLib\n");
        return header.toString();
    }

    private void validateMainConfig(
            github.freshchromatic.freshlib.config.configurate.validation.ValidationResult result) {
        FreshLibConfig config = mainConfigManager.config();
    }

    /**
     * Enables automatic file watching for all configurations.
     */
    public void enableAutoReload() {
        if (mainConfigManager != null) {
            mainConfigManager.enableAutoReload();
        }
    }

    /**
     * Disables automatic file watching.
     */
    public void disableAutoReload() {
        if (mainConfigManager != null) {
            mainConfigManager.disableAutoReload();
        }
    }

    /**
     * Generates configuration documentation for all config files.
     */
    public void generateDocumentation(Path outputDir) {
        try {
            Path docPath = outputDir.resolve("configuration.md");
            ConfigDocGenerator.generateMarkdown(FreshLibConfig.class, docPath);

            Path messagesDoc = outputDir.resolve("messages-configuration.md");
            ConfigDocGenerator.generateMarkdown(Messages.class, messagesDoc);

            Logging.logger().info("Generated documentation at: " + outputDir);
        } catch (Exception e) {
            Logging.logger().severe("Failed to generate documentation: " + e.getMessage());
        }
    }

    public FreshLibConfig config() {
        return mainConfigManager != null ? mainConfigManager.config() : null;
    }

    public Messages messages() {
        return messagesManager != null ? messagesManager.config() : null;
    }

    public void saveMainConfig() {
        if (mainConfigManager != null) {
            mainConfigManager.save();
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    /**
     * Shuts down the configuration manager and releases resources.
     */
    public void shutdown() {
        disableAutoReload();
        if (mainConfigManager != null) {
            mainConfigManager.shutdown();
        }
        initialized.set(false);
        Logging.logger().info("Configuration manager shut down");
    }
}

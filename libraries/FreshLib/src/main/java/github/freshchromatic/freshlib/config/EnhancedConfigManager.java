package github.freshchromatic.freshlib.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import github.freshchromatic.freshlib.config.configurate.ConfigDocGenerator;
import github.freshchromatic.freshlib.config.configurate.ConfigFileName;
import github.freshchromatic.freshlib.config.configurate.EnvironmentConfigManager;
import github.freshchromatic.freshlib.config.configurate.TranslatedConfigManager;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public final class EnhancedConfigManager {

    private final Path                                                                                                       dataDirectory;
    private final JavaPlugin                                                                                                 plugin;
    private github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader> mainConfigManager;
    private TranslatedConfigManager<Messages>                                                                                messagesManager;
    private final AtomicBoolean                                                                                              initialized = new AtomicBoolean(
        false);
    private boolean                                                                                                          autoReloadEnabled;

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

            var nextConfigManager = new github.freshchromatic.freshlib.config.configurate.EnhancedConfigManager<FreshLibConfig, YamlConfigurationLoader>(
                configPath, FreshLibConfig.class) {

                @Override
                protected String header() {
                    return buildHeader();
                }
            };

            Path backupPath = dataDirectory.resolve("backups").resolve("config-" + System.currentTimeMillis()
                + ".yml.bak");

            boolean configLoaded = nextConfigManager.tryLoad(true, true, backupPath);
            if (!configLoaded && mainConfigManager != null) {
                return;
            }

            FreshLibConfig config = nextConfigManager.config();
            if (config == null) {
                return;
            }

            if (config.automaticConfigurationUpgrade) {
                Logging.logger().info("Automatic configuration upgrade is ENABLED");
                Logging.logger().info("Missing options will be automatically added with default values on next reload");
            } else {
                Logging.logger().info("Automatic configuration upgrade is DISABLED");
                Logging.logger().warning("New configuration options will NOT be automatically added!");
            }

            var configuredFile = ConfigFileName.parse(config.settings.languageFile);
            if (configuredFile.isEmpty() && messagesManager != null) {
                return;
            }
            if (configuredFile.isEmpty()) {
                Logging.logger().warning("Invalid language file name; using lang-en.yml for initial configuration");
            }
            ConfigFileName langFile = configuredFile.orElseGet(() -> new ConfigFileName("lang-en.yml"));
            Path langPath = EnvironmentConfigManager.resolveConfigPath(dataDirectory.resolve("locale"), langFile);

            var nextMessagesManager = new TranslatedConfigManager<>(langPath, Messages.class, "/locale/"
                + langFile.value());
            boolean messagesLoaded = nextMessagesManager.tryLoad();
            if (!messagesLoaded && messagesManager != null) {
                return;
            }
            if (nextMessagesManager.config() == null) {
                return;
            }

            if (mainConfigManager != null) {
                mainConfigManager.shutdown();
            }
            mainConfigManager = nextConfigManager;
            messagesManager = nextMessagesManager;
            if (autoReloadEnabled) {
                mainConfigManager.enableAutoReload();
            }

            initialized.set(true);
            if (configLoaded && messagesLoaded) {
                Logging.logger().info("Configuration reloaded successfully");
            } else {
                Logging.logger().warning("Configuration initialized with defaults after a load failure");
            }

        } catch (Exception e) {
            Logging.logger().severe("Failed to initialize configuration", e);
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

    /**
     * Enables automatic file watching for all configurations.
     */
    public void enableAutoReload() {
        autoReloadEnabled = true;
        if (mainConfigManager != null) {
            mainConfigManager.enableAutoReload();
        }
    }

    /**
     * Disables automatic file watching.
     */
    public void disableAutoReload() {
        autoReloadEnabled = false;
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

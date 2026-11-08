package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.config.configurate.annotation.Constants;
import github.freshchromatic.freshlib.config.configurate.cache.ConfigCache;
import github.freshchromatic.freshlib.config.configurate.serializer.CustomSerializers;
import github.freshchromatic.freshlib.config.configurate.validation.ValidationResult;
import github.freshchromatic.freshlib.util.Logging;
import io.leangen.geantyref.TypeToken;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.loader.AbstractConfigurationLoader;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enhanced abstract configuration manager with full DiscordSRV feature parity.
 *
 * <p>Features:
 * <ul>
 *   <li>Generic type support with dual ObjectMapper factories</li>
 *   <li>Custom naming scheme (camelCase to lower-case-dashed)</li>
 *   <li>Rich annotation support (@Order, @Comment, @Constants, @DefaultOnly)</li>
 *   <li>Comprehensive validation system</li>
 *   <li>Automatic comment insertion via post-processing</li>
 *   <li>Missing option detection and warning</li>
 *   <li>Backup mechanism for configuration files</li>
 *   <li>ThreadLocal state management</li>
 *   <li>Multi-environment support</li>
 * </ul></p>
 *
 * @param <T> the configuration type
 * @param <LT> the loader type
 */
public abstract class EnhancedConfigManager<T extends Config, LT extends AbstractConfigurationLoader<? extends CommentedConfigurationNode>> {

    protected static final ThreadLocal<Boolean> DEFAULT_CONFIG = ThreadLocal.withInitial(() -> false);
    protected static final ThreadLocal<Boolean> SAVE_OR_LOAD   = ThreadLocal.withInitial(() -> false);

    private final Path                        path;
    private final Class<T>                    configClass;
    private final TypeToken<T>                typeToken;
    private T                                 config;
    private LT                                loader;
    private ConfigCache<T>                    cache;
    private final ConfigMissingOptionDetector missingOptionDetector = new ConfigMissingOptionDetector();
    private final AtomicBoolean               initialized           = new AtomicBoolean(false);

    public EnhancedConfigManager(Path path, Class<T> configClass) {
        this.path = path;
        this.configClass = configClass;
        this.typeToken = TypeToken.get(configClass);
    }

    protected Path getPath() {
        return path;
    }

    protected Class<T> getConfigClass() {
        return configClass;
    }

    /**
     * Returns whether we are currently generating a default configuration.
     */
    public static boolean isGeneratingDefault() {
        return DEFAULT_CONFIG.get();
    }

    /**
     * Returns whether we are currently in a save or load operation.
     */
    public static boolean isSaveOrLoad() {
        return SAVE_OR_LOAD.get();
    }

    /**
     * Creates the primary ObjectMapper factory with all features enabled.
     */
    protected ObjectMapper.Factory objectMapperFactory() {
        return ObjectMapperFactoryProvider.objectMapperFactory();
    }

    /**
     * Creates a clean ObjectMapper factory that respects @DefaultOnly annotation.
     */
    protected ObjectMapper.Factory cleanObjectMapperFactory() {
        return ObjectMapperFactoryProvider.cleanObjectMapperFactory(isGeneratingDefault());
    }

    /**
     * Returns the configuration options including serializers and header.
     */
    protected ConfigurationOptions options() {
        return ConfigurationOptions.defaults()
            .header(resolveHeader())
            .serializers(
            builder ->
                builder.registerAnnotatedObjects(objectMapperFactory())
                    .registerAll(TypeSerializerCollection.defaults())
                    .registerAll(customSerializers()));
    }

    /**
     * Resolves the header from the config class's getHeader() method.
     */
    protected String resolveHeader() {
        T instance = createInstance();
        if (instance != null) {
            String header = instance.getHeader();
            if (header != null) {
                return replaceConstants(header, instance.getClass());
            }
        }
        return header();
    }

    protected String header() {
        return null;
    }

    /**
     * Returns custom type serializers for this configuration manager.
     */
    protected TypeSerializerCollection customSerializers() {
        return CustomSerializers.createAll();
    }

    /**
     * Creates the configuration loader. Override to support different formats.
     */
    @SuppressWarnings("unchecked")
    protected LT createLoader() {
        return (LT) YamlConfigurationLoader.builder()
            .path(path)
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(options())
            .build();
    }

    /**
     * Loads configuration from disk with full validation and merge support.
     *
     * @param forceSave if true, always save after loading
     * @param detectMissingOptions if true, log warnings for missing options
     * @param backupPath if not null, create backup before modifications
     */
    public void load(boolean forceSave, boolean detectMissingOptions, Path backupPath) {
        tryLoad(forceSave, detectMissingOptions, backupPath);
    }

    public boolean tryLoad(boolean forceSave, boolean detectMissingOptions, Path backupPath) {
        SAVE_OR_LOAD.set(true);
        try {
            if (loader == null) {
                loader = createLoader();
            }

            if (config == null) {
                config = createInstance();
            }

            CommentedConfigurationNode node;

            if (!Files.exists(path)) {
                Logging.logger().info("Creating default configuration: " + path.getFileName());
                DEFAULT_CONFIG.set(true);
                try {
                    saveDefault();
                } finally {
                    DEFAULT_CONFIG.set(false);
                }
                initialized.set(true);
                if (cache != null) {
                    cache.set(config);
                }
                return true;
            }

            node = loader.load();

            T defaults = createInstance();
            CommentedConfigurationNode defaultNode = loader.createNode(options());

            DEFAULT_CONFIG.set(true);
            try {
                defaultNode.set(typeToken, defaults);
            } finally {
                DEFAULT_CONFIG.set(false);
            }

            List<ConfigNodePath> missingKeys = missingOptionDetector.find(defaultNode, node, configClass);
            boolean hasMissingOptions = !missingKeys.isEmpty();

            if (detectMissingOptions && hasMissingOptions) {
                logMissingOptions(missingKeys);
            }

            if (backupPath != null && hasMissingOptions) {
                createBackup(backupPath);
            }

            boolean shouldAutoUpgrade = shouldPerformAutoUpgrade(node, hasMissingOptions);

            if (shouldAutoUpgrade && hasMissingOptions) {
                Logging.logger().info("Automatically upgrading configuration: " + path.getFileName());
                Logging.logger().info("Adding " + missingKeys.size() + " missing option(s) with default values");

                // Only add missing options to the current node instead of merging defaults over everything.
                // This prevents adding back intentional removals from maps.
                for (ConfigNodePath key : missingKeys) {
                    key.copyDefaultTo(node, defaultNode);
                }

                T loaded = Objects.requireNonNull(node.get(typeToken));
                saveCurrent(loaded);
                config = loaded;
                if (cache != null) {
                    cache.set(config);
                }

                Logging.logger().info("Configuration upgrade completed successfully for: " + path.getFileName());
            } else {
                T loaded = Objects.requireNonNull(node.get(typeToken));

                if (forceSave) {
                    saveCurrent(loaded);
                }
                config = loaded;
                if (cache != null) {
                    cache.set(config);
                }
            }

            ValidationResult validation = validate();
            if (validation.hasErrors()) {
                Logging.logger().severe("Validation errors in " + path.getFileName() + ":");
                for (ValidationResult.ValidationError error : validation.getErrors()) {
                    Logging.logger().severe("  " + error.toString());
                }
            }
            if (validation.hasWarnings()) {
                Logging.logger().warning("Validation warnings in " + path.getFileName() + ":");
                for (ValidationResult.ValidationWarning warning : validation.getWarnings()) {
                    Logging.logger().warning("  " + warning.toString());
                }
            }

            initialized.set(true);

            return true;
        } catch (ConfigurateException e) {
            Logging.logger().severe("Failed to load configuration " + path.getFileName(), e);
            return false;
        } finally {
            SAVE_OR_LOAD.set(false);
        }
    }

    /**
     * Determines if automatic configuration upgrade should be performed.
     * Checks the automaticConfigurationUpgrade setting from the loaded config.
     *
     * @param node the loaded configuration node
     * @param hasMissingOptions whether there are any missing options
     * @return true if auto-upgrade should be performed
     */
    private boolean shouldPerformAutoUpgrade(CommentedConfigurationNode node, boolean hasMissingOptions) {
        if (!hasMissingOptions) {
            return false;
        }

        try {
            Boolean autoUpgradeSetting = node.node("automatic-configuration-upgrade").getBoolean();
            if (autoUpgradeSetting != null) {
                return autoUpgradeSetting;
            }
        } catch (Exception e) {
            Logging.logger().warning("Failed to read automatic-configuration-upgrade setting, defaulting to true");
        }

        return true;
    }

    /**
     * Logs missing options with detailed information.
     *
     * @param missingKeys list of missing configuration keys
     */
    private void logMissingOptions(List<ConfigNodePath> missingKeys) {
        if (missingKeys.isEmpty()) {
            return;
        }

        Logging.logger().warning("Detected " + missingKeys.size() + " missing option(s) in " + path.getFileName()
            + ":");

        for (ConfigNodePath key : missingKeys) {
            Logging.logger().warning("  - Missing option: \"" + key + "\"");
        }

        Logging.logger().info("These options will be added with their default values.");
    }

    public void load() {
        load(true, true, null);
    }

    public void reload() {
        load();
    }

    /**
     * Saves current configuration to disk with comment processing.
     */
    public void save() {
        save(config);
        if (cache != null) {
            cache.set(config);
        }
    }

    private void save(T value) {
        SAVE_OR_LOAD.set(true);
        try {
            saveCurrent(value);
            config = value;
        } catch (ConfigurateException e) {
            throw new IllegalStateException("Failed to save configuration " + path.getFileName(), e);
        } finally {
            SAVE_OR_LOAD.set(false);
        }
    }

    private void saveCurrent(T value) throws ConfigurateException {
        CommentedConfigurationNode node = loader.createNode(options());
        node.set(typeToken, value);
        saveWithComments(node);
    }

    /**
     * Saves a default configuration to disk.
     */
    protected void saveDefault() throws ConfigurateException {
        T defaults = createInstance();
        CommentedConfigurationNode node = loader.createNode(options());
        node.set(typeToken, defaults);
        saveWithComments(node);
        config = defaults;
    }

    /**
     * Validates the current configuration.
     * Override to add custom validation logic.
     */
    protected ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        validateSpecificFields(result);
        return result;
    }

    protected void validateSpecificFields(ValidationResult result) {
    }

    /**
     * Creates a backup of the current configuration file.
     */
    protected void createBackup(Path backupPath) {
        if (!Files.exists(path)) {
            return;
        }

        try {
            if (backupPath.getParent() != null) {
                Files.createDirectories(backupPath.getParent());
            }
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
            Logging.logger().info("Created configuration backup: " + backupPath);
        } catch (IOException e) {
            Logging.logger().warning("Failed to create configuration backup: " + e.getMessage());
        }
    }

    /**
     * Saves node to disk with custom comment insertion (same as original AbstractConfigManager).
     */
    protected void saveWithComments(ConfigurationNode node) throws ConfigurateException {
        if (!(node instanceof CommentedConfigurationNode)) {
            loader.save(node);
            return;
        }

        new YamlCommentWriter(path).save((CommentedConfigurationNode) node);
    }

    /**
     * Replaces constants (%1, %2, etc.) in a string based on @Constants annotations.
     */
    protected String replaceConstants(String input, Class<?> clazz) {
        Constants constantsAnnotation = clazz.getAnnotation(Constants.class);
        if (constantsAnnotation == null) {
            return input;
        }

        Map<String, String> constantMap = new HashMap<>();
        for (String def : constantsAnnotation.value()) {
            int eqIdx = def.indexOf('=');
            if (eqIdx > 0) {
                String key = def.substring(0, eqIdx).trim();
                String value = def.substring(eqIdx + 1).trim();
                constantMap.put(key, value);
            }
        }

        String result = input;
        for (Map.Entry<String, String> entry : constantMap.entrySet()) {
            int index = 0;
            for (String def : constantsAnnotation.value()) {
                if (def.startsWith(entry.getKey() + "=")) {
                    result = result.replace("%" + (index + 1), entry.getValue());
                    break;
                }
                index++;
            }
        }
        return result;
    }

    /**
     * Nulls all non-primitive, non-final, non-static fields of an object.
     * Based on DiscordSRV's nullAllFields method.
     */
    public static void nullAllFields(Object obj) {
        if (obj == null) {
            return;
        }

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    continue;
                }
                if (field.getType().isPrimitive()) {
                    continue;
                }

                field.setAccessible(true);
                try {
                    field.set(obj, null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Unable to clear configuration field " + field, e);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Gets or creates the configuration cache for this manager.
     */
    public ConfigCache<T> getOrCreateCache() {
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = new ConfigCache<T>(path, ignored -> {
                        if (tryLoad(true, true, null)) {
                            Logging.logger().info("Configuration reloaded: " + path.getFileName());
                        }
                    }, this::save);
                    if (initialized.get()) {
                        cache.set(config);
                    }
                }
            }
        }
        return cache;
    }

    /**
     * Enables automatic file watching for hot-reload.
     */
    public void enableAutoReload() {
        getOrCreateCache().startWatching();
    }

    /**
     * Disables automatic file watching.
     */
    public void disableAutoReload() {
        if (cache != null) {
            cache.stopWatching();
        }
    }

    /**
     * Invalidates any cached configuration.
     */
    public void invalidateCache() {
        if (cache != null) {
            cache.invalidate();
        }
    }

    public T config() {
        return config;
    }

    public Path getConfigPath() {
        return path;
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    protected T createInstance() {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + configClass.getName(), e);
        }
    }

    public void shutdown() {
        disableAutoReload();
        if (cache != null) {
            cache.clearChangeListeners();
        }
    }
}

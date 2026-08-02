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
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    protected static final ThreadLocal<Boolean> SAVE_OR_LOAD = ThreadLocal.withInitial(() -> false);

    private final Path path;
    private final Class<T> configClass;
    private final TypeToken<T> typeToken;
    private T config;
    private LT loader;
    private ConfigCache<T> cache;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

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
                .serializers(builder -> builder
                        .registerAnnotatedObjects(objectMapperFactory())
                        .registerAll(TypeSerializerCollection.defaults())
                        .registerAll(customSerializers())
                );
    }

    /**
     * Resolves the header from the config class's getHeader() method.
     */
    protected String resolveHeader() {
        try {
            T instance = createInstance();
            if (instance != null) {
                String header = instance.getHeader();
                if (header != null) {
                    return replaceConstants(header, instance.getClass());
                }
            }
        } catch (Exception ignored) {}
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
                return;
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

            List<String> missingKeys = findMissingKeys(defaultNode, node, "", configClass);
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
                for (String key : missingKeys) {
                    node.node((Object[]) key.split("\\.")).set(defaultNode.node((Object[]) key.split("\\.")));
                }

                config = Objects.requireNonNull(node.get(typeToken));
                save();

                Logging.logger().info("Configuration upgrade completed successfully for: " + path.getFileName());
            } else {
                config = Objects.requireNonNull(node.get(typeToken));

                if (forceSave || (hasMissingOptions && shouldAutoUpgrade)) {
                    save();
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

        } catch (ConfigurateException e) {
            Logging.logger().severe("Failed to load configuration " + path.getFileName() + ": " + e.getMessage());
            e.printStackTrace();
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
    private void logMissingOptions(List<String> missingKeys) {
        if (missingKeys.isEmpty()) return;

        Logging.logger().warning("Detected " + missingKeys.size() + " missing option(s) in " + path.getFileName() + ":");

        for (String key : missingKeys) {
            Logging.logger().warning("  - Missing option: \"" + key + "\"");
        }

        Logging.logger().info("These options will be added with their default values.");
    }

    public void load() {
        load(true, true, null);
    }

    public void reload() {
        invalidateCache();
        load();
    }

    /**
     * Saves current configuration to disk with comment processing.
     */
    public void save() {
        SAVE_OR_LOAD.set(true);
        try {
            CommentedConfigurationNode node = loader.createNode(options());
            node.set(typeToken, config);
            saveWithComments(node);
            if (cache != null) {
                cache.set(config);
            }
        } catch (ConfigurateException e) {
            Logging.logger().severe("Failed to save configuration " + path.getFileName() + ": " + e.getMessage());
        } finally {
            SAVE_OR_LOAD.set(false);
        }
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

    protected void validateSpecificFields(ValidationResult result) {}

    private List<String> findMissingKeys(ConfigurationNode defaults, ConfigurationNode loaded, String prefix, Class<?> clazz) {
        List<String> missing = new ArrayList<>();
        if (clazz == null) return missing;

        Map<String, Field> fieldsByDashedName = getFieldsByDashedName(clazz);

        for (Map.Entry<Object, ? extends ConfigurationNode> entry : defaults.childrenMap().entrySet()) {
            String nodeKey = entry.getKey().toString();
            String fullPath = prefix.isEmpty() ? nodeKey : prefix + "." + nodeKey;

            Field field = fieldsByDashedName.get(nodeKey);
            
            // If this node does not correspond to a field, it's a Map entry.
            // Map entries are not "missing options".
            if (field == null) continue;

            ConfigurationNode loadedChild = loaded.node(nodeKey);
            if (loadedChild.virtual()) {
                missing.add(fullPath);
            } else if (entry.getValue().isMap()) {
                // Determine if we should recurse. We recurse into @ConfigSerializable objects.
                Class<?> fieldType = field.getType();
                if (fieldType.isAnnotationPresent(ConfigSerializable.class)) {
                    missing.addAll(findMissingKeys(entry.getValue(), loadedChild, fullPath, fieldType));
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    // It's a Map field. We don't report missing keys, but we can recurse into existing ones 
                    // if they are ConfigSerializable objects.
                    Type genericType = field.getGenericType();
                    if (genericType instanceof ParameterizedType) {
                        Type valueType = ((ParameterizedType) genericType).getActualTypeArguments()[1];
                        if (valueType instanceof Class && ((Class<?>) valueType).isAnnotationPresent(ConfigSerializable.class)) {
                            Class<?> valueClass = (Class<?>) valueType;
                            for (Map.Entry<Object, ? extends ConfigurationNode> mapEntry : entry.getValue().childrenMap().entrySet()) {
                                ConfigurationNode loadedMapEntry = loadedChild.node(mapEntry.getKey());
                                if (!loadedMapEntry.virtual()) {
                                    missing.addAll(findMissingKeys(mapEntry.getValue(), loadedMapEntry, fullPath + "." + mapEntry.getKey(), valueClass));
                                }
                            }
                        }
                    }
                }
            }
        }

        return missing;
    }

    private Map<String, Field> getFieldsByDashedName(Class<?> clazz) {
        Map<String, Field> map = new HashMap<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                String dashedName = ObjectMapperFactoryProvider.getNamingScheme().coerce(field.getName());
                map.putIfAbsent(dashedName, field);
            }
            current = current.getSuperclass();
        }
        return map;
    }

    private boolean hasNewDefaults(CommentedConfigurationNode defaultNode, ConfigurationNode loadedNode) {
        return !findMissingKeys(defaultNode, loadedNode, "", configClass).isEmpty();
    }

    /**
     * Creates a backup of the current configuration file.
     */
    protected void createBackup(Path backupPath) {
        if (!Files.exists(path)) return;

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

        CommentedConfigurationNode commentedNode = (CommentedConfigurationNode) node;

        StringWriter sw = new StringWriter();
        YamlConfigurationLoader tempLoader = YamlConfigurationLoader.builder()
                .sink(() -> new BufferedWriter(sw))
                .nodeStyle(NodeStyle.BLOCK)
                .defaultOptions(node.options())
                .build();
        tempLoader.save(node);

        Map<String, String> commentMap = new LinkedHashMap<>();
        buildCommentMap("", commentedNode, commentMap);

        String yaml = sw.toString();
        String output = commentMap.isEmpty() ? yaml : insertComments(yaml, commentMap);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurateException(e);
        }
    }

    private static void buildCommentMap(String prefix, CommentedConfigurationNode node, Map<String, String> out) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            ConfigurationNode child = entry.getValue();
            String key = entry.getKey().toString();
            String pathStr = prefix.isEmpty() ? key : prefix + "." + key;

            if (child instanceof CommentedConfigurationNode) {
                String comment = ((CommentedConfigurationNode) child).comment();
                if (comment != null && !comment.isEmpty()) {
                    out.put(pathStr, comment);
                }
                buildCommentMap(pathStr, (CommentedConfigurationNode) child, out);
            }
        }
    }

    private static final Pattern KEY_LINE = Pattern.compile("^( *)([a-zA-Z0-9_-]+):(.*)$");

    private static String insertComments(String yaml, Map<String, String> commentMap) {
        StringBuilder result = new StringBuilder();
        String[] lines = yaml.split("\n", -1);

        List<String> keyStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();

        for (String line : lines) {
            String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;

            Matcher m = KEY_LINE.matcher(normalized);
            if (m.matches()) {
                int indent = m.group(1).length();
                String key = m.group(2);

                while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                    indentStack.remove(indentStack.size() - 1);
                    keyStack.remove(keyStack.size() - 1);
                }
                keyStack.add(key);
                indentStack.add(indent);

                String dotPath = String.join(".", keyStack);
                String comment = commentMap.get(dotPath);
                if (comment != null) {
                    String indentStr = m.group(1);
                    for (String commentLine : comment.split("\n", -1)) {
                        result.append(indentStr).append("# ").append(commentLine).append("\n");
                    }
                }
            }

            result.append(line).append("\n");
        }

        String out = result.toString();
        if (out.endsWith("\n\n") && !yaml.endsWith("\n\n")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * Replaces constants (%1, %2, etc.) in a string based on @Constants annotations.
     */
    protected String replaceConstants(String input, Class<?> clazz) {
        Constants constantsAnnotation = clazz.getAnnotation(Constants.class);
        if (constantsAnnotation == null) return input;

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
        if (obj == null) return;

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                if (field.getType().isPrimitive()) continue;

                field.setAccessible(true);
                try {
                    field.set(obj, null);
                } catch (IllegalAccessException ignored) {}
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
                    cache = new ConfigCache<T>(
                            path,
                            (ignored) -> load(),
                            (ignored) -> save()
                    );
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

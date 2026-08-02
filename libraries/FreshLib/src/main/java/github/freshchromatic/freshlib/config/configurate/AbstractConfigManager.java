package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.Config;
import github.freshchromatic.freshlib.config.Messages;
import github.freshchromatic.freshlib.config.configurate.annotation.Order;
import github.freshchromatic.freshlib.config.configurate.fielddiscoverer.FieldValueDiscovererProxy;
import github.freshchromatic.freshlib.config.configurate.fielddiscoverer.OrderedFieldDiscovererProxy;
import github.freshchromatic.freshlib.config.configurate.serializer.ComponentMessageSerializer;
import github.freshchromatic.freshlib.config.configurate.serializer.StringMessageSerializer;
import github.freshchromatic.freshlib.util.Logging;
import io.leangen.geantyref.TypeToken;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.loader.AbstractConfigurationLoader;
import org.spongepowered.configurate.objectmapping.FieldDiscoverer;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Processor;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractConfigManager<T extends Config> {

    protected final Path path;
    protected final Class<T> configClass;
    private final TypeToken<T> typeToken;
    protected T config;
    protected AbstractConfigurationLoader<? extends CommentedConfigurationNode> loader;

    public AbstractConfigManager(Path path, Class<T> configClass) {
        this.path = path;
        this.configClass = configClass;
        this.typeToken = TypeToken.get(configClass);
    }

    protected ObjectMapper.Factory objectMapperFactory() {
        Comparator<OrderedFieldDiscovererProxy.FieldCollectorData<Object, ?>> fieldOrder = Comparator.comparingInt(data -> {
            Order order = data.annotations().getAnnotation(Order.class);
            return order != null ? order.value() : 0;
        });

        return ObjectMapper.factoryBuilder()
                .defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED)
                .addDiscoverer(new OrderedFieldDiscovererProxy<>((FieldDiscoverer<Object>) (Object) FieldValueDiscovererProxy.EMPTY_CONSTRUCTOR_INSTANCE, fieldOrder))
                .addDiscoverer(new OrderedFieldDiscovererProxy<>((FieldDiscoverer<Object>) FieldDiscoverer.record(), fieldOrder))
                .addProcessor(Comment.class, Processor.comments())
                .build();
    }

    protected ConfigurationOptions options() {
        return ConfigurationOptions.defaults()
                .header(header())
                .serializers(builder -> builder
                        .registerAnnotatedObjects(objectMapperFactory())
                        .registerAll(TypeSerializerCollection.defaults())
                        .registerAll(customSerializers())
                );
    }

    protected String header() {
        return null;
    }

    protected TypeSerializerCollection customSerializers() {
        return TypeSerializerCollection.builder()
                .register(Messages.ComponentMessage.class, new ComponentMessageSerializer())
                .register(Messages.StringMessage.class, new StringMessageSerializer())
                .build();
    }

    protected AbstractConfigurationLoader<? extends CommentedConfigurationNode> createLoader() {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .defaultOptions(options())
                .build();
    }

    public void load() {
        if (loader == null) {
            loader = createLoader();
        }

        // Initialize with default instance in case load fails
        if (config == null) {
            config = createInstance();
        }

        try {
            CommentedConfigurationNode node = loader.load();

            // Create a "clean" node with defaults from the POJO
            T defaults = createInstance();
            CommentedConfigurationNode defaultNode = loader.createNode(options());
            defaultNode.set(typeToken, defaults);

            // Merge only missing keys. ConfigurationNode.mergeFrom() also considers an
            // empty scalar (for example, permission: "") empty, and would replace it
            // with the default value.
            mergeDefaults(node, defaultNode);
            beforeDeserialize(node);

            // Populate the config object
            config = Objects.requireNonNull(node.get(typeToken));
            afterLoad(config, node);

            // Save back to disk to ensure new options/comments are present.
            // Must re-serialize from config (not defaultNode) so that @Comment processors
            // are re-applied on a fresh node — mergeFrom() clears comments on leaf nodes
            // by copying the comment-less disk node via from(), wiping annotations-derived comments.
            save();

        } catch (ConfigurateException e) {
            Logging.logger().severe("Failed to load configuration " + path.getFileName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void save() {
        /* Future Implement
        if (loader == null) {
            loader = createLoader();
        }
        if (config == null) {
            config = createInstance();
        }
        */
        try {
            CommentedConfigurationNode node = loader.createNode(options());
            node.set(typeToken, config);
            save(node);
        } catch (ConfigurateException e) {
            Logging.logger().severe("Failed to save configuration " + path.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Saves the node to disk. If the node is a CommentedConfigurationNode, comments from
     * @Comment annotations (applied via Processor.comments()) are written to the YAML file.
     *
     * <p>Configurate 4.1.2's YamlConfigurationLoader.saveInternal() only writes node.raw()
     * and does not emit CommentedConfigurationNode comments. This override post-processes
     * the generated YAML to insert those comments before each annotated key.</p>
     */
    protected void save(ConfigurationNode node) throws ConfigurateException {
        if (!(node instanceof CommentedConfigurationNode)) {
            loader.save(node);
            return;
        }

        CommentedConfigurationNode commentedNode = (CommentedConfigurationNode) node;

        // Generate YAML to a string (without comments — Configurate 4.1.2 doesn't write them)
        StringWriter sw = new StringWriter();
        YamlConfigurationLoader tempLoader = YamlConfigurationLoader.builder()
                .sink(() -> new BufferedWriter(sw))
                .nodeStyle(NodeStyle.BLOCK)
                .defaultOptions(node.options())
                .build();
        tempLoader.save(node);

        // Build map of yaml-path → comment from the CommentedConfigurationNode tree
        Map<String, String> commentMap = new LinkedHashMap<>();
        buildCommentMap("", commentedNode, commentMap);

        // Insert comments into YAML text and write to file
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

    /**
     * Recursively collects all non-null comments from the node tree into a flat map.
     * Keys are dot-separated YAML paths, e.g. "settings.language-file".
     */
    private static void buildCommentMap(String prefix, CommentedConfigurationNode node, Map<String, String> out) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            ConfigurationNode child = entry.getValue();
            String key = entry.getKey().toString();
            String path = prefix.isEmpty() ? key : prefix + "." + key;

            if (child instanceof CommentedConfigurationNode) {
                String comment = ((CommentedConfigurationNode) child).comment();
                if (comment != null && !comment.isEmpty()) {
                    out.put(path, comment);
                }
                buildCommentMap(path, (CommentedConfigurationNode) child, out);
            }
        }
    }

    private static final Pattern KEY_LINE = Pattern.compile("^( *)([a-zA-Z0-9_-]+):(.*)$");

    /**
     * Inserts YAML-style block comments (#) before each key line whose dot-path
     * has an entry in {@code commentMap}.
     */
    private static String insertComments(String yaml, Map<String, String> commentMap) {
        StringBuilder result = new StringBuilder();
        String[] lines = yaml.split("\n", -1);

        List<String> keyStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();

        for (String line : lines) {
            // Strip Windows-style CR if present
            String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;

            Matcher m = KEY_LINE.matcher(normalized);
            if (m.matches()) {
                int indent = m.group(1).length();
                String key = m.group(2);

                // Pop stack entries whose indent is >= current line's indent
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

        // The split/rejoin adds one trailing newline; remove it if the original didn't end with \n\n
        String out = result.toString();
        if (out.endsWith("\n\n") && !yaml.endsWith("\n\n")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public T config() {
        return config;
    }

    /**
     * Allows a configuration to normalize values that cannot be represented directly
     * by the object mapper before the normalized configuration is saved.
     */
    protected void afterLoad(T config, ConfigurationNode node) {
    }

    /**
     * Allows a configuration manager to normalize legacy or shorthand YAML node shapes before
     * Configurate maps them to the strongly typed configuration object.
     */
    protected void beforeDeserialize(ConfigurationNode node) throws ConfigurateException {
    }

    /** Merges defaults into missing configuration paths. Subclasses may preserve collection nodes. */
    protected void mergeDefaults(ConfigurationNode loaded, ConfigurationNode defaults) {
        mergeMissingDefaults(loaded, defaults);
    }

    private static void mergeMissingDefaults(ConfigurationNode loaded, ConfigurationNode defaults) {
        if (loaded.virtual()) {
            loaded.from(defaults);
            return;
        }

        if (!loaded.isMap() || !defaults.isMap()) {
            return;
        }

        for (Map.Entry<Object, ? extends ConfigurationNode> entry : defaults.childrenMap().entrySet()) {
            mergeMissingDefaults(loaded.node(entry.getKey()), entry.getValue());
        }
    }

    protected T createInstance() {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + configClass.getName(), e);
        }
    }
}

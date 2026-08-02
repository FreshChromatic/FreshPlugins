package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.configurate.annotation.DefaultOnly;
import github.freshchromatic.freshlib.config.configurate.annotation.Order;
import github.freshchromatic.freshlib.config.configurate.fielddiscoverer.FieldValueDiscovererProxy;
import github.freshchromatic.freshlib.config.configurate.fielddiscoverer.OrderedFieldDiscovererProxy;
import org.spongepowered.configurate.objectmapping.FieldDiscoverer;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Processor;
import org.spongepowered.configurate.util.NamingScheme;
import org.spongepowered.configurate.util.NamingSchemes;

import java.util.Comparator;

/**
 * Utility class providing dual ObjectMapper factories with custom naming schemes.
 * Based on DiscordSRV's implementation.
 *
 * <p>Provides two factory types:
 * <ul>
 *   <li>{@link #objectMapperFactory()} - Full version with comment processing</li>
 *   <li>{@link #cleanObjectMapperFactory()} - Clean version supporting @DefaultOnly filtering</li>
 * </ul></p>
 */
public final class ObjectMapperFactoryProvider {

    /**
     * Custom naming scheme: first character lowercase + LOWER_CASE_DASHED
     * Based on DiscordSRV's NAMING_SCHEME
     */
    public static final NamingScheme NAMING_SCHEME = in -> {
        in = Character.toLowerCase(in.charAt(0)) + in.substring(1);
        in = NamingSchemes.LOWER_CASE_DASHED.coerce(in);
        return in;
    };

    private static final Comparator<OrderedFieldDiscovererProxy.FieldCollectorData<Object, ?>> FIELD_ORDER =
            Comparator.comparingInt(data -> {
                Order order = data.annotations().getAnnotation(Order.class);
                return order != null ? order.value() : 0;
            });

    private ObjectMapperFactoryProvider() {
    }

    /**
     * Creates a full ObjectMapper factory with all processors enabled.
     * This factory supports:
     * - Custom naming scheme (camelCase to lower-case-dashed)
     * - Field ordering via @Order annotation
     * - Comment processing via @Comment annotation
     *
     * @return configured ObjectMapper.Factory instance
     */
    public static ObjectMapper.Factory objectMapperFactory() {
        return ObjectMapper.factoryBuilder()
                .defaultNamingScheme(NAMING_SCHEME)
                .addDiscoverer(new OrderedFieldDiscovererProxy<>(
                        (FieldDiscoverer<Object>) (Object) FieldValueDiscovererProxy.EMPTY_CONSTRUCTOR_INSTANCE,
                        FIELD_ORDER))
                .addDiscoverer(new OrderedFieldDiscovererProxy<>(
                        (FieldDiscoverer<Object>) FieldDiscoverer.record(),
                        FIELD_ORDER))
                .addProcessor(Comment.class, Processor.comments())
                .build();
    }

    /**
     * Creates a clean ObjectMapper factory that filters out @DefaultOnly annotated fields
     * when not generating default configurations.
     *
     * <p>This factory is useful for:
     * <ul>
     *   <li>Loading user configurations (hides default-only fields)</li>
     *   <li>Creating minimal configuration outputs</li>
     * </ul></p>
     *
     * @param isGeneratingDefaults whether we are currently generating a default config
     * @return configured ObjectMapper.Factory instance with DefaultOnly support
     */
    public static ObjectMapper.Factory cleanObjectMapperFactory(boolean isGeneratingDefaults) {
        ObjectMapper.Factory.Builder builder = ObjectMapper.factoryBuilder()
                .defaultNamingScheme(NAMING_SCHEME)
                .addDiscoverer(new OrderedFieldDiscovererProxy<>(
                        (FieldDiscoverer<Object>) (Object) FieldValueDiscovererProxy.EMPTY_CONSTRUCTOR_INSTANCE,
                        FIELD_ORDER))
                .addDiscoverer(new OrderedFieldDiscovererProxy<>(
                        (FieldDiscoverer<Object>) FieldDiscoverer.record(),
                        FIELD_ORDER));

        if (!isGeneratingDefaults) {
            builder.addProcessor(DefaultOnly.class, (annotation, value) -> null);
        }

        return builder.addProcessor(Comment.class, Processor.comments())
                .build();
    }

    /**
     * Returns the standard naming scheme constant for external use.
     *
     * @return the custom NamingScheme instance
     */
    public static NamingScheme getNamingScheme() {
        return NAMING_SCHEME;
    }
}

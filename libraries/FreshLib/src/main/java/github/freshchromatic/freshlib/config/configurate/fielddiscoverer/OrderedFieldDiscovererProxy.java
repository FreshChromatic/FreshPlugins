package github.freshchromatic.freshlib.config.configurate.fielddiscoverer;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.FieldData;
import org.spongepowered.configurate.objectmapping.FieldDiscoverer;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.util.CheckedFunction;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Proxy for ordering fields discovered by another discoverer.
 * Based on DiscordSRV's implementation.
 */
public class OrderedFieldDiscovererProxy<T> implements FieldDiscoverer<T> {

    private final FieldDiscoverer<T> fieldDiscoverer;
    private final Comparator<FieldCollectorData<T, ?>> order;

    public OrderedFieldDiscovererProxy(FieldDiscoverer<T> fieldDiscoverer, Comparator<FieldCollectorData<T, ?>> order) {
        this.fieldDiscoverer = fieldDiscoverer;
        this.order = order;
    }

    @Override
    public @Nullable <V> InstanceFactory<T> discover(AnnotatedType target, FieldCollector<T, V> collector) throws SerializationException {
        List<FieldCollectorData<T, V>> data = new ArrayList<>();
        FieldCollector<T, V> fieldCollector = (name, type, annotations, deserializer, serializer) ->
                data.add(new FieldCollectorData<>(name, type, annotations, deserializer, serializer));

        InstanceFactory<T> instanceFactory = fieldDiscoverer.discover(target, fieldCollector);
        if (instanceFactory == null) {
            return null;
        }

        data.sort(order);
        for (FieldCollectorData<T, V> field : data) {
            collector.accept(field.name, field.type, field.annotations, field.deserializer, field.serializer);
        }

        return instanceFactory;
    }

    public static class FieldCollectorData<T, V> {

        private final String name;
        private final AnnotatedType type;
        private final AnnotatedElement annotations;
        private final FieldData.Deserializer<T> deserializer;
        private final CheckedFunction<V, Object, Exception> serializer;

        public FieldCollectorData(String name, AnnotatedType type, AnnotatedElement annotations,
                                  FieldData.Deserializer<T> deserializer,
                                  CheckedFunction<V, Object, Exception> serializer) {
            this.name = name;
            this.type = type;
            this.annotations = annotations;
            this.deserializer = deserializer;
            this.serializer = serializer;
        }

        public String name() {
            return name;
        }

        public AnnotatedType type() {
            return type;
        }

        public AnnotatedElement annotations() {
            return annotations;
        }
    }
}

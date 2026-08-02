package github.freshchromatic.freshlib.config.configurate.serializer;

import github.freshchromatic.freshlib.config.Messages.ComponentMessage;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;

public class ComponentMessageSerializer implements TypeSerializer<ComponentMessage> {

    @Override
    public ComponentMessage deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String val = node.getString();
        if (val == null) {
            throw new SerializationException("ComponentMessage must be a string");
        }
        return new ComponentMessage(val);
    }

    @Override
    public void serialize(Type type, @Nullable ComponentMessage obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.miniMessage());
        }
    }
}

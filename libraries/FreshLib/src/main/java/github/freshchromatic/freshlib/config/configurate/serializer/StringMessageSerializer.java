package github.freshchromatic.freshlib.config.configurate.serializer;

import github.freshchromatic.freshlib.config.Messages.StringMessage;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;

public class StringMessageSerializer implements TypeSerializer<StringMessage> {

    @Override
    public StringMessage deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String val = node.getString();
        if (val == null) {
            throw new SerializationException("StringMessage must be a string");
        }
        return new StringMessage(val);
    }

    @Override
    public void serialize(Type type, @Nullable StringMessage obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.message());
        }
    }
}

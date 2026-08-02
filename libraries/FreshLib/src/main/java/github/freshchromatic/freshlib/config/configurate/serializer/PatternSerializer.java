package github.freshchromatic.freshlib.config.configurate.serializer;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Serializer for java.util.regex.Pattern objects.
 * Stores patterns as their string representation.
 *
 * <p>Based on DiscordSRV's PatternSerializer.</p>
 */
public class PatternSerializer implements TypeSerializer<Pattern> {

    @Override
    public Pattern deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String value = node.getString();
        if (value == null) {
            throw new SerializationException("Pattern value must be a string");
        }

        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new SerializationException(node, type, "Invalid regex pattern: " + value, e);
        }
    }

    @Override
    public void serialize(Type type, @Nullable Pattern obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.pattern());
        }
    }
}

package github.freshchromatic.freshlib.config.configurate.serializer;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * Serializer for java.net.URL objects.
 *
 * <p>Based on DiscordSRV's URL handling.</p>
 */
public class URLSerializer implements TypeSerializer<URL> {

    @Override
    public URL deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String value = node.getString();
        if (value == null) {
            throw new SerializationException("URL value must be a string");
        }

        try {
            return URI.create(value).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new SerializationException(node, type, "Invalid URL: " + value, e);
        }
    }

    @Override
    public void serialize(Type type, @Nullable URL obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.toString());
        }
    }
}

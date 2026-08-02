package github.freshchromatic.freshlib.config.configurate.serializer;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.awt.Color;
import java.lang.reflect.Type;

/**
 * Serializer for java.awt.Color objects.
 * Supports hex color format (#RRGGBB) and RGB format (R,G,B).
 *
 * <p>Based on DiscordSRV's ColorSerializer.</p>
 */
public class ColorSerializer implements TypeSerializer<Color> {

    @Override
    public Color deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String value = node.getString();
        if (value == null) {
            throw new SerializationException("Color value must be a string");
        }

        try {
            if (value.startsWith("#")) {
                return Color.decode(value);
            } else if (value.contains(",")) {
                String[] parts = value.split(",");
                if (parts.length == 3) {
                    return new Color(
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim())
                    );
                }
            }
            throw new SerializationException("Invalid color format: " + value);
        } catch (NumberFormatException e) {
            throw new SerializationException(node, type, "Invalid color value: " + value, e);
        }
    }

    @Override
    public void serialize(Type type, @Nullable Color obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(String.format("#%02X%02X%02X", obj.getRed(), obj.getGreen(), obj.getBlue()));
        }
    }
}

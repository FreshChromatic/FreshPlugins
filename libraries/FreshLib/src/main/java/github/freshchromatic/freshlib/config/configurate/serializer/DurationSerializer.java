package github.freshchromatic.freshlib.config.configurate.serializer;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.time.Duration;

/**
 * Serializer for java.time.Duration objects.
 * Supports multiple formats:
 * - ISO-8601 duration format (PT1H30M)
 * - Simple format (1h30m, 45s, 500ms)
 * - Milliseconds as number
 *
 * <p>Based on DiscordSRV's Duration handling.</p>
 */
public class DurationSerializer implements TypeSerializer<Duration> {

    @Override
    public Duration deserialize(Type type, ConfigurationNode node) throws SerializationException {
        if (node.isList() || node.isMap()) {
            throw new SerializationException("Duration must be a string or number");
        }

        Object raw = node.raw();
        if (raw instanceof Number) {
            return Duration.ofMillis(((Number) raw).longValue());
        }

        String value = node.getString();
        if (value == null) {
            throw new SerializationException("Duration value must be a string or number");
        }

        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Duration.parse(value);
            }
            return parseSimpleFormat(value);
        } catch (Exception e) {
            throw new SerializationException(node, type, "Invalid duration format: " + value, e);
        }
    }

    private Duration parseSimpleFormat(String input) {
        long totalMillis = 0;
        StringBuilder numBuffer = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c) || c == '.' || (c == '-' && numBuffer.length() == 0)) {
                numBuffer.append(c);
            } else {
                if (numBuffer.length() > 0) {
                    double num = Double.parseDouble(numBuffer.toString());
                    switch (Character.toLowerCase(c)) {
                        case 'd': totalMillis += (long) (num * 86400000); break;
                        case 'h': totalMillis += (long) (num * 3600000); break;
                        case 'm':
                            if (i + 1 < input.length() && Character.toLowerCase(input.charAt(i + 1)) == 's') {
                                totalMillis += (long) num;
                                i++;
                            } else {
                                totalMillis += (long) (num * 60000);
                            }
                            break;
                        case 's': totalMillis += (long) (num * 1000); break;
                        default: throw new IllegalArgumentException("Unknown duration unit: " + c);
                    }
                    numBuffer = new StringBuilder();
                }
            }
        }

        return Duration.ofMillis(totalMillis);
    }

    @Override
    public void serialize(Type type, @Nullable Duration obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.toMillis());
        }
    }
}

package github.freshchromatic.freshlib.config.configurate.serializer;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.EnumSet;
import java.util.Set;

/**
 * Enhanced enum serializer that provides case-insensitive deserialization
 * and validation with available values in error messages.
 *
 * <p>Based on DiscordSRV's EnumSerializer.</p>
 */
public class EnumSerializer<T extends Enum<T>> implements TypeSerializer<T> {

    private final Class<T> enumClass;

    public EnumSerializer(Class<T> enumClass) {
        this.enumClass = enumClass;
    }

    @Override
    public T deserialize(Type type, ConfigurationNode node) throws SerializationException {
        String value = node.getString();
        if (value == null) {
            throw new SerializationException("Enum value must be a string for type: " + enumClass.getSimpleName());
        }

        try {
            return Enum.valueOf(enumClass, value.toUpperCase().replace("-", "_").replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            Set<T> constants = EnumSet.allOf(enumClass);
            StringBuilder validValues = new StringBuilder();
            for (T constant : constants) {
                if (validValues.length() > 0) {
                    validValues.append(", ");
                }
                validValues.append(constant.name());
            }
            throw new SerializationException(
                    "Invalid enum value '" + value + "' for " + enumClass.getSimpleName() +
                    ". Valid values: [" + validValues + "]"
            );
        }
    }

    @Override
    public void serialize(Type type, @Nullable T obj, ConfigurationNode node) throws SerializationException {
        if (obj == null) {
            node.set(null);
        } else {
            node.set(obj.name().toLowerCase().replace("_", "-"));
        }
    }
}

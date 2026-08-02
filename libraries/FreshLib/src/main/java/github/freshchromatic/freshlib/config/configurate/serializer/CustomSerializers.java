package github.freshchromatic.freshlib.config.configurate.serializer;

import github.freshchromatic.freshlib.config.Messages;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;

import java.awt.Color;
import java.net.URL;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Registry of all custom type serializers for the configuration system.
 * Provides easy access to all custom serializers and ability to register them.
 *
 * <p>Based on DiscordSRV's serializer collection approach.</p>
 */
public final class CustomSerializers {

    private CustomSerializers() {
    }

    /**
     * Creates a TypeSerializerCollection containing all custom serializers.
     *
     * @return configured TypeSerializerCollection
     */
    public static TypeSerializerCollection createAll() {
        return TypeSerializerCollection.builder()
                .register(Color.class, new ColorSerializer())
                .register(Pattern.class, new PatternSerializer())
                .register(URL.class, new URLSerializer())
                .register(Duration.class, new DurationSerializer())
                .register(Messages.ComponentMessage.class, new ComponentMessageSerializer())
                .register(Messages.StringMessage.class, new StringMessageSerializer())
                .build();
    }

    /**
     * Creates a TypeSerializerCollection with only essential custom serializers.
     * Useful for lightweight configurations or translation files.
     *
     * @return minimal TypeSerializerCollection
     */
    public static TypeSerializerCollection createMinimal() {
        return TypeSerializerCollection.builder()
                .register(Messages.ComponentMessage.class, new ComponentMessageSerializer())
                .register(Messages.StringMessage.class, new StringMessageSerializer())
                .build();
    }
}

package github.freshchromatic.freshlib.config.configurate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field to only be included when generating default configurations.
 * When this annotation is present, the field will only appear in the config file
 * if it has a non-default value during normal operation.
 *
 * <p>Based on DiscordSRV's @DefaultOnly annotation.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DefaultOnly {
}

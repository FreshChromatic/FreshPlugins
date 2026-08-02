package github.freshchromatic.freshlib.config.configurate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines constants that can be used in configuration values and comments.
 * Constants are referenced using %1, %2, etc. syntax.
 *
 * <p>Based on DiscordSRV's @Constants annotation.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Constants({
 *     "plugin-name=FreshLib",
 *     "version=1.0.0"
 * })
 * public class MyConfig {
 *     @Comment("The name of %1")
 *     public String name = "%1";
 * }
 * }</pre></p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Constants {

    /**
     * Array of constant definitions in key=value format.
     */
    String[] value();
}

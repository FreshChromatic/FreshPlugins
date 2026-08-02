package github.freshchromatic.freshlib.config.configurate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specialized comment annotation for use with constants.
 * Allows comments to reference constants defined via {@link Constants}.
 *
 * <p>Based on DiscordSRV's @Constants.Comment annotation.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConstantsComment {

    /**
     * The comment text, which may contain constant references (%1, %2, etc.)
     */
    String value();
}

package github.freshchromatic.freshlib.config.configurate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as untranslated, meaning it should not be processed
 * by translation systems even if other fields in the same class are translated.
 *
 * <p>Based on DiscordSRV's @Untranslated annotation.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Untranslated {
}

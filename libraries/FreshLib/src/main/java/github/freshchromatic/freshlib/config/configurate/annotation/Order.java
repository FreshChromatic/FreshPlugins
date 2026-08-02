package github.freshchromatic.freshlib.config.configurate.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Manually determines the position of the option in the config, everything is ordered {@code 0} by default,
 * and will go in the order they are defined.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Order {

    /**
     * Lowest to highest.
     * @return the order value of the option
     */
    int value();
}

package github.freshchromatic.freshlib.config.configurate.validation;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Validator for configuration values providing common validation rules.
 *
 * <p>Based on DiscordSRV's configuration validation approach.</p>
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Validates a configuration node against the given predicate.
     *
     * @param node the configuration node to validate
     * @param path the dot-separated path for error reporting
     * @param predicate the validation predicate
     * @param errorMessage error message if validation fails
     * @param result the validation result to record errors to
     */
    public static void validate(ConfigurationNode node, String path,
                                Predicate<ConfigurationNode> predicate,
                                String errorMessage, ValidationResult result) {
        try {
            if (!predicate.test(node)) {
                result.addError(path, errorMessage);
            }
        } catch (Exception e) {
            result.addError(path, errorMessage + " (validation failed: " + e.getMessage() + ")");
        }
    }

    /**
     * Validates that a node is not null/empty.
     */
    public static void requireNonEmpty(ConfigurationNode node, String path, ValidationResult result) {
        if (node.virtual() || node.empty()) {
            result.addError(path, "Value is required and cannot be empty");
        }
    }

    /**
     * Validates that a numeric value is within range.
     */
    public static void requireRange(ConfigurationNode node, String path,
                                    Number min, Number max, ValidationResult result) {
        if (node.virtual()) return;

        try {
            Number value = (Number) node.get(Number.class);
            if (value == null) {
                result.addError(path, "Expected numeric value");
                return;
            }

            BigDecimal bdValue = BigDecimal.valueOf(value.doubleValue());
            BigDecimal bdMin = BigDecimal.valueOf(min.doubleValue());
            BigDecimal bdMax = BigDecimal.valueOf(max.doubleValue());

            if (bdMin != null && bdValue.compareTo(bdMin) < 0) {
                result.addError(path, "Value must be at least " + min);
            }
            if (bdMax != null && bdValue.compareTo(bdMax) > 0) {
                result.addError(path, "Value must be at most " + max);
            }
        } catch (SerializationException e) {
            result.addError(path, "Invalid numeric value: " + e.getMessage());
        }
    }

    /**
     * Validates that a string value matches a pattern.
     */
    public static void requirePattern(ConfigurationNode node, String path,
                                      String regex, ValidationResult result) {
        if (node.virtual()) return;

        String value = node.getString();
        if (value == null || !value.matches(regex)) {
            result.addError(path, "Value must match pattern: " + regex);
        }
    }

    /**
     * Validates that a string value is one of the allowed options.
     */
    public static void requireOneOf(ConfigurationNode node, String path,
                                   Collection<String> allowedValues, ValidationResult result) {
        if (node.virtual()) return;

        String value = node.getString();
        if (value == null || !allowedValues.contains(value)) {
            result.addError(path, "Value must be one of: " + allowedValues);
        }
    }

    /**
     * Validates that a collection has a minimum size.
     */
    public static void requireMinSize(ConfigurationNode node, String path,
                                     int minSize, ValidationResult result) {
        if (node.virtual() || !node.isList()) return;

        try {
            Collection<?> items = node.getList(Object.class);
            if (items != null && items.size() < minSize) {
                result.addError(path, "Must have at least " + minSize + " item(s)");
            }
        } catch (SerializationException e) {
            result.addError(path, "Invalid list format: " + e.getMessage());
        }
    }

    /**
     * Validates that a map contains required keys.
     */
    public static void requireKeys(ConfigurationNode node, String path,
                                  Collection<String> requiredKeys, ValidationResult result) {
        if (node.virtual() || !node.isMap()) return;

        Map<Object, ? extends ConfigurationNode> map = node.childrenMap();
        for (String key : requiredKeys) {
            if (!map.containsKey(key)) {
                result.addError(path + "." + key, "Required key is missing");
            }
        }
    }

    /**
     * Records a warning for deprecated or unusual configurations.
     */
    public static void warnDeprecated(ConfigurationNode node, String path,
                                      String suggestion, ValidationResult result) {
        if (!node.virtual()) {
            result.addWarning(path, "Deprecated: " + suggestion);
        }
    }
}

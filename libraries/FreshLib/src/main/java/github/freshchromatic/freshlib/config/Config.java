package github.freshchromatic.freshlib.config;

/**
 * Enhanced marker interface for configuration POJOs.
 * Based on DiscordSRV's Config interface with added file binding support.
 */
public interface Config {

    /**
     * Returns the file name this configuration should be saved to.
     * This allows configuration objects to know their associated file.
     *
     * @return the configuration file name (e.g., "config.yml")
     */
    default String getFileName() {
        return "config.yml";
    }

    /**
     * Returns whether this configuration should be validated before saving.
     *
     * @return true if validation should be performed
     */
    default boolean requiresValidation() {
        return true;
    }

    /**
     * Returns the configuration header comment that will be written at the top of the file.
     *
     * @return the header text, or null for no header
     */
    default String getHeader() {
        return null;
    }
}

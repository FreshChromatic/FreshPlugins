package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.util.Logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-environment configuration support system.
 *
 * <p>Allows configuration to be switched based on environment profiles:
 * <ul>
 *   <li>Development (dev)</li>
 *   <li>Testing (test)</li>
 *   <li>Staging (staging)</li>
 *   <li>Production (prod)</li>
 * </ul></p>
 *
 * <p>Configuration files follow the naming convention:
 * config-{profile}.yml or use environment-specific directories.</p>
 *
 * <p>Based on DiscordSRV's multi-environment approach with enhanced profile management.</p>
 */
public class EnvironmentConfigManager {

    public enum Environment {
        DEV("dev", "development"),
        TEST("test", "testing"),
        STAGING("staging", "stage"),
        PROD("prod", "production");

        private final String id;
        private final String[] aliases;

        Environment(String id, String... aliases) {
            this.id = id;
            this.aliases = aliases;
        }

        public String getId() { return id; }

        public boolean matches(String value) {
            if (id.equalsIgnoreCase(value)) return true;
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(value)) return true;
            }
            return false;
        }

        public static Environment fromString(String value) {
            if (value == null || value.isEmpty()) return PROD;
            for (Environment env : values()) {
                if (env.matches(value)) return env;
            }
            Logging.logger().warning("Unknown environment: '" + value + "', defaulting to production");
            return PROD;
        }
    }

    private static Environment currentEnvironment = Environment.PROD;
    private static final Map<String, String> environmentVariables = new ConcurrentHashMap<>();
    private static final List<String> ACTIVE_PROFILES = Arrays.asList("default");

    private EnvironmentConfigManager() {
    }

    /**
     * Sets the current environment.
     */
    public static void setEnvironment(Environment environment) {
        currentEnvironment = environment;
        Logging.logger().info("Environment set to: " + environment.getId());
    }

    /**
     * Sets the current environment from a string.
     */
    public static void setEnvironment(String environment) {
        setEnvironment(Environment.fromString(environment));
    }

    /**
     * Gets the current environment.
     */
    public static Environment getEnvironment() {
        return currentEnvironment;
    }

    /**
     * Checks if we're running in development mode.
     */
    public static boolean isDev() {
        return currentEnvironment == Environment.DEV;
    }

    /**
     * Checks if we're running in production mode.
     */
    public static boolean isProduction() {
        return currentEnvironment == Environment.PROD;
    }

    /**
     * Adds an active Spring-style profile.
     */
    public static void addActiveProfile(String profile) {
        if (!ACTIVE_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
            ACTIVE_PROFILES.add(profile.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Checks if a profile is currently active.
     */
    public static boolean isProfileActive(String profile) {
        return ACTIVE_PROFILES.contains(profile.toLowerCase(Locale.ROOT))
                || ACTIVE_PROFILES.contains(currentEnvironment.getId());
    }

    /**
     * Gets all active profiles.
     */
    public static List<String> getActiveProfiles() {
        return List.copyOf(ACTIVE_PROFILES);
    }

    /**
     * Sets an environment variable that can be used in configurations.
     */
    public static void setVariable(String key, String value) {
        environmentVariables.put(key, value);
    }

    /**
     * Gets an environment variable value.
     */
    public static String getVariable(String key) {
        String value = environmentVariables.get(key);
        if (value != null) return value;

        value = System.getenv(key);
        if (value != null) return value;

        value = System.getProperty(key);
        if (value != null) return value;

        return null;
    }

    /**
     * Resolves a configuration path based on the current environment.
     * Looks for environment-specific file first, then falls back to base file.
     *
     * @param basePath the base configuration directory
     * @param fileName the base file name (e.g., "config.yml")
     * @return resolved path
     */
    public static Path resolveConfigPath(Path basePath, String fileName) {
        Path envSpecificPath = basePath.resolve(
                currentEnvironment.getId() + "-" + fileName.replace(".yml", "") + ".yml"
        );

        if (Files.exists(envSpecificPath)) {
            return envSpecificPath;
        }

        return basePath.resolve(fileName);
    }

    /**
     * Resolves environment variables in a string using ${VAR} syntax.
     */
    public static String resolveEnvironmentVariables(String input) {
        if (input == null || !input.contains("${")) return input;

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (input.charAt(i) == '$' && i + 1 < input.length() && input.charAt(i + 1) == '{') {
                int end = input.indexOf('}', i + 2);
                if (end > 0) {
                    String varName = input.substring(i + 2, end);
                    String varValue = getVariable(varName);
                    result.append(varValue != null ? varValue : "");
                    i = end + 1;
                    continue;
                }
            }
            result.append(input.charAt(i));
            i++;
        }
        return result.toString();
    }

    /**
     * Resets the environment manager to defaults (useful for testing).
     */
    public static void reset() {
        currentEnvironment = Environment.PROD;
        environmentVariables.clear();
        ACTIVE_PROFILES.clear();
        ACTIVE_PROFILES.add("default");
    }
}

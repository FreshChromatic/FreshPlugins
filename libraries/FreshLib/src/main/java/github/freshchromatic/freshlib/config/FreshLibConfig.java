package github.freshchromatic.freshlib.config;

import github.freshchromatic.freshlib.config.configurate.annotation.Order;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;

@ConfigSerializable
public final class FreshLibConfig implements Config {

    @Comment("Automatically upgrade configuration files on startup or reload if any values are missing.\n"
            + "When enabled, missing configuration options will be added with their default values\n"
            + "and the configuration file will be updated with new options and comments.")
    @Order(0)
    public boolean automaticConfigurationUpgrade = true;

    @Order(1)
    public Settings settings = new Settings();

    @Override
    public String getFileName() {
        return "config.yml";
    }

    @Override
    public String getHeader() {
        return "FreshLib Configuration\n" +
                "Plugin: FreshLib\n" +
                "Generated with all available options documented.\n";
    }

    @ConfigSerializable
    public static class Settings {
        @Order(1)
        @Comment("Language file to load from the locale/ folder (e.g., 'lang-en.yml').")
        public String languageFile = "lang-en.yml";

        @Order(2)
        public Commands commands = new Commands();
    }

    @ConfigSerializable
    public static class Commands {
        @Order(1)
        @Comment("Primary command name used to invoke the plugin (e.g., /freshlib).")
        public String mainCommandLabel = "freshlib";

        @Order(2)
        @Comment("Short aliases for the main command (e.g., /fl). Add as many as you like.")
        public List<String> mainCommandAliases = List.of("fl");
    }
}

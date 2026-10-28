package github.freshchromatic.chunkrevive.bootstrap;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.freshlib.config.configurate.ConfigurateConfigManager;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;

import java.nio.file.Path;
import java.util.Map;

/** ChunkRevive-specific configuration loading and YAML shorthand normalization. */
final class ChunkReviveConfigManager extends ConfigurateConfigManager<PluginConfig> {
    private final String pluginVersion;

    ChunkReviveConfigManager(Path path, String pluginVersion) {
        super(path, PluginConfig.class);
        this.pluginVersion = pluginVersion;
    }

    @Override
    protected void mergeDefaults(ConfigurationNode loaded, ConfigurationNode defaults) {
        migrateLandProtection(loaded);
        ConfigurationNode tracked = trackedStructures(loaded);
        boolean explicitlyConfigured = !tracked.virtual();
        Object configuredValue = explicitlyConfigured ? tracked.raw() : null;

        super.mergeDefaults(loaded, defaults);

        if (explicitlyConfigured) {
            trackedStructures(loaded).raw(configuredValue);
        }
    }

    private static void migrateLandProtection(ConfigurationNode root) {
        var oldScan = root.node("scan", "check-residence-claims");
        var newScan = root.node("land-protection", "scan-claims");
        if (newScan.virtual() && !oldScan.virtual()) newScan.raw(oldScan.raw());

        var oldPolicy = root.node("structure", "residence", "on-partial-claim");
        var newPolicy = root.node("land-protection", "default-structure-policy");
        if (newPolicy.virtual() && !oldPolicy.virtual()) newPolicy.raw(oldPolicy.raw());
    }

    @Override
    protected void beforeDeserialize(ConfigurationNode node) throws ConfigurateException {
        ConfigurationNode tracked = trackedStructures(node);
        if (tracked.isList() && tracked.childrenList().isEmpty()) {
            tracked.set(Map.of());
        }
    }

    @Override
    protected String header() {
        return "ChunkRevive Configuration File\nVersion: " + pluginVersion + "\n";
    }

    private static ConfigurationNode trackedStructures(ConfigurationNode root) {
        return root.node("structure", "refresh", "tracked-structures");
    }
}

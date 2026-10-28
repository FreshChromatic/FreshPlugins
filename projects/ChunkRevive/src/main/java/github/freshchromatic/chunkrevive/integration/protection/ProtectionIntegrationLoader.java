package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.integration.residence.ResidenceIntegration;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ProtectionIntegrationLoader {
    private ProtectionIntegrationLoader() {}

    public static ProtectionIntegration load(Supplier<PluginConfig.LandProtection> config) {
        List<ProtectionIntegration> integrations = new ArrayList<>();
        add(integrations, config.get(), "Residence", "residence", ResidenceIntegration::new);
        add(integrations, config.get(), "GriefPrevention", "griefprevention", GriefPreventionProtection::new);
        add(integrations, config.get(), "GriefDefender", "griefdefender", GriefDefenderProtection::new);
        add(integrations, config.get(), "Towny", "towny", TownyProtection::new);
        add(integrations, config.get(), "WorldGuard", "worldguard", WorldGuardProtection::new);
        add(integrations, config.get(), "Dominion", "dominion", DominionProtection::new);
        add(integrations, config.get(), "Homestead", "homestead", HomesteadProtection::new);
        add(integrations, config.get(), "CCP", "ccp", ChunkClaimProtection::new);
        add(integrations, config.get(), "LandClaimPlugin", "landclaimplugin", LandClaimProtection::new);
        add(integrations, config.get(), "Bonfire", "bonfire",
            () -> new BonfireProtection(Bukkit.getPluginManager().getPlugin("Bonfire")));
        return new CombinedProtectionIntegration(integrations, config);
    }

    private static void add(List<ProtectionIntegration> integrations, PluginConfig.LandProtection config,
                            String pluginName, String id, Supplier<ProtectionIntegration> factory) {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) return;
        if (!config.providers.byId(id).enabled) {
            Logging.logger().warning("Land protection integration disabled by configuration: " + pluginName);
            return;
        }
        integrations.add(new GuardedProtectionIntegration(Bukkit.getPluginManager().getPlugin(pluginName), factory.get()));
        Logging.logger().info("Land protection integration enabled: " + pluginName);
    }
}

package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.integration.residence.ResidenceIntegration;
import github.freshchromatic.freshlib.util.Logging;
import org.bukkit.Bukkit;

/** Selects the best available land-protection adapter without leaking vendor classes into bootstrap. */
public final class ProtectionIntegrationLoader {
    private ProtectionIntegrationLoader() {}

    public static ProtectionIntegration load() {
        var residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence != null && residence.isEnabled()) {
            Logging.logger().info("Residence found — integration enabled.");
            return new ResidenceIntegration();
        }
        Logging.logger().info("Residence not found — integration disabled.");
        return new NoProtectionIntegration();
    }
}

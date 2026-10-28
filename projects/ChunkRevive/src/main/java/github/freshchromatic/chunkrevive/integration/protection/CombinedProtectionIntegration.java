package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class CombinedProtectionIntegration implements ProtectionIntegration, LandProtection {
    private final List<ProtectionIntegration> integrations;
    private final Supplier<PluginConfig.LandProtection> config;

    public CombinedProtectionIntegration(List<ProtectionIntegration> integrations,
                                         Supplier<PluginConfig.LandProtection> config) {
        this.integrations = List.copyOf(integrations);
        this.config = config;
    }

    @Override
    public LandProtection landProtection() {
        return this;
    }

    @Override
    public boolean isEnabled() {
        return !integrations.isEmpty();
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        return integrations.stream().anyMatch(integration -> integration.landProtection().hasClaim(world, x, z));
    }

    @Override
    public void registerListeners(Plugin plugin, MarkService marks) {
        integrations.forEach(integration -> integration.registerListeners(plugin, marks));
    }

    @Override
    public void registerProviders(IntegrationApi api) {
        integrations.forEach(integration -> integration.registerProviders(api));
    }

    @Override
    public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
                                               PluginConfig.Structure.PartialClaimPolicy defaultPolicy) {
        World bukkitWorld = Bukkit.getWorld(world);
        return resolveEffectiveChunks(bukkitWorld, minX, maxX, minZ, maxZ, defaultPolicy);
    }

    List<int[]> resolveEffectiveChunks(World bukkitWorld, int minX, int maxX, int minZ, int maxZ,
                                       PluginConfig.Structure.PartialClaimPolicy defaultPolicy) {
        List<int[]> result = new ArrayList<>();
        PluginConfig.LandProtection current = config.get();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean excluded = false;
                for (ProtectionIntegration integration : integrations) {
                    String id = ((ProtectionProvider) integration).id();
                    PluginConfig.Structure.PartialClaimPolicy policy = current.policy(id, defaultPolicy);
                    if (policy == PluginConfig.Structure.PartialClaimPolicy.IGNORE_CLAIMS || bukkitWorld == null
                            || !integration.landProtection().hasClaim(bukkitWorld, x, z)) continue;
                    if (policy == PluginConfig.Structure.PartialClaimPolicy.ABORT_WHOLE) return null;
                    excluded = true;
                }
                if (!excluded) result.add(new int[]{x, z});
            }
        }
        return result;
    }
}

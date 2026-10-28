package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletionStage;

final class GuardedProtectionIntegration implements ProtectionIntegration, LandProtection, ProtectionProvider {
    private final Plugin                owner;
    private final ProtectionIntegration delegate;
    private final ProtectionProvider    provider;

    GuardedProtectionIntegration(Plugin owner, ProtectionIntegration delegate) {
        this.owner = owner;
        this.delegate = delegate;
        this.provider = (ProtectionProvider) delegate;
    }

    @Override
    public String id() {
        return provider.id();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public LandProtection landProtection() {
        return this;
    }

    @Override
    public boolean hasClaim(World world, int x, int z) {
        requireAvailable();
        return delegate.landProtection().hasClaim(world, x, z);
    }

    @Override
    public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
                                              PluginConfig.Structure.PartialClaimPolicy policy) {
        requireAvailable();
        return delegate.landProtection().resolveEffectiveChunks(world, minX, maxX, minZ, maxZ, policy);
    }

    @Override
    public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
        requireAvailable();
        return provider.check(query);
    }

    @Override
    public void registerProviders(IntegrationApi api) {
        delegate.registerProviders(api);
    }

    @Override
    public void registerListeners(Plugin plugin, MarkService marks) {
        delegate.registerListeners(plugin, marks);
    }

    private void requireAvailable() {
        if (!owner.isEnabled()) {
            throw new IllegalStateException("Land protection plugin is unavailable: " + owner.getName());
        }
    }
}

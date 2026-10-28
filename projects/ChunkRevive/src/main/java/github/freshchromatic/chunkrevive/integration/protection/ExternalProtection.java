package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

abstract class ExternalProtection implements ProtectionIntegration, LandProtection, ProtectionProvider {
    private final String pluginName;

    ExternalProtection(String pluginName) {
        this.pluginName = pluginName;
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
    public String id() {
        return pluginName.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void registerProviders(IntegrationApi api) {
        api.registerProtectionProvider(Bukkit.getPluginManager().getPlugin(pluginName), this);
    }

    @Override
    public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
        var decisions = new HashMap<ChunkKey, ProtectionDecision>();
        var reasons = new HashMap<ChunkKey, String>();
        for (ChunkKey chunk : query.chunks()) {
            World world = Bukkit.getWorld(chunk.world());
            boolean claimed = world != null && hasClaim(world, chunk.x(), chunk.z());
            decisions.put(chunk, claimed ? ProtectionDecision.DENY : ProtectionDecision.ABSTAIN);
            if (claimed) {
                reasons.put(chunk, id().toUpperCase(java.util.Locale.ROOT) + "_CLAIM");
            }
        }
        return CompletableFuture.completedFuture(new ProtectionBatchResult(decisions, reasons));
    }
    @Override
    public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
            PluginConfig.Structure.PartialClaimPolicy policy) {
        return new CombinedProtectionIntegration(List.of(this), PluginConfig.LandProtection::new).resolveEffectiveChunks(world, minX, maxX, minZ, maxZ, policy);
    }
}

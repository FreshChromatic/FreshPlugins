package github.freshchromatic.chunkrevive.integration.residence;

import com.bekvon.bukkit.residence.protection.ResidenceManager.ChunkRef;
import com.bekvon.bukkit.residence.Residence;
import github.freshchromatic.chunkrevive.api.integration.ChunkArea;
import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionChange;
import github.freshchromatic.chunkrevive.api.integration.ProtectionChangeType;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.api.integration.ProtectionRegistration;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import github.freshchromatic.chunkrevive.integration.protection.LandProtection;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionIntegration;
import github.freshchromatic.chunkrevive.nms.ChunkCoordinate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ResidenceIntegration implements LandProtection, ProtectionIntegration, ProtectionProvider {

    private volatile ProtectionRegistration registration;

    public ResidenceIntegration() {
    }

    public boolean isEnabled() {
        return true;
    }

    @Override
    public LandProtection landProtection() {
        return this;
    }

    @Override
    public void registerListeners(Plugin plugin, MarkService markService) {
        Bukkit.getPluginManager().registerEvents(new ResidenceClaimListener(plugin, markService,
            this::notifyClaimCreated), plugin);
    }

    /**
     * Returns true if any Residence overlaps the given chunk (including subzones).
     * Uses Residence's chunk index so claims at any Y level are detected without loading the chunk.
     */
    public boolean hasClaim(World world, int cx, int cz) {
        var api = Residence.getInstance().getResidenceManager();
        return !api.getResidences(world.getName(), List.of(new ChunkRef(cx, cz))).isEmpty();
    }

    /**
     * Batch-resolves a chunk range against Residence claims, per the given partial-claim policy.
     *
     * @return EXCLUDE_CLAIMED: the unclaimed chunk coordinates in range (possibly empty).
     *         ABORT_WHOLE: {@code null} if any chunk in range is claimed, otherwise all chunks in range.
     *         IGNORE_CLAIMS: all chunks in range, without querying Residence at all.
     */
    @Nullable
    public List<ChunkCoordinate> resolveEffectiveChunks(String world, int minCx, int maxCx, int minCz, int maxCz,
        PluginConfig.Structure.PartialClaimPolicy policy) {
        if (policy == PluginConfig.Structure.PartialClaimPolicy.IGNORE_CLAIMS) {
            List<ChunkCoordinate> all = new ArrayList<>();
            for (int x = minCx; x <= maxCx; x++) {
                for (int z = minCz; z <= maxCz; z++) {
                    all.add(new ChunkCoordinate(x, z));
                }
            }
            return all;
        }

        var w = Bukkit.getWorld(world);
        List<ChunkCoordinate> unclaimed = new ArrayList<>();
        for (int x = minCx; x <= maxCx; x++) {
            for (int z = minCz; z <= maxCz; z++) {
                boolean claimed = w != null && hasClaim(w, x, z);
                if (claimed) {
                    if (policy == PluginConfig.Structure.PartialClaimPolicy.ABORT_WHOLE) {
                        return null;
                    }
                } else {
                    unclaimed.add(new ChunkCoordinate(x, z));
                }
            }
        }
        return unclaimed;
    }

    @Override
    public String id() {
        return "residence";
    }

    @Override
    public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
        Map<ChunkKey, ProtectionDecision> decisions = new HashMap<>();
        Map<ChunkKey, String> reasons = new HashMap<>();
        for (ChunkKey chunk : query.chunks()) {
            World world = Bukkit.getWorld(chunk.world());
            if (world != null && hasClaim(world, chunk.x(), chunk.z())) {
                decisions.put(chunk, ProtectionDecision.DENY);
                reasons.put(chunk, "RESIDENCE_CLAIM");
            } else {
                decisions.put(chunk, ProtectionDecision.ABSTAIN);
            }
        }
        return CompletableFuture.completedFuture(new ProtectionBatchResult(decisions, reasons));
    }

    @Override
    public void registerProviders(IntegrationApi api) {
        registration = api.registerProtectionProvider(Bukkit.getPluginManager().getPlugin("Residence"), this);
    }

    private void notifyClaimCreated(String world, int minCx, int maxCx, int minCz, int maxCz) {
        ProtectionRegistration current = registration;
        if (current != null) {
            current.notifyChanged(new ProtectionChange(ProtectionChangeType.CREATED, Optional.of(new ChunkArea(world,
                minCx, maxCx, minCz, maxCz))));
        }
    }

}

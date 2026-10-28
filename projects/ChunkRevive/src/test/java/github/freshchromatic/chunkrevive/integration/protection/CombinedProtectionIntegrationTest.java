package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.config.PluginConfig;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedProtectionIntegrationTest {
    private static final World WORLD = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
        new Class<?>[]{World.class}, (proxy, method, args) -> null);

    @Test
    void perProviderExclusionsDoNotOverrideEachOther() {
        var config = new PluginConfig.LandProtection();
        config.providers.towny.structurePolicy = "IGNORE_CLAIMS";
        config.providers.worldguard.structurePolicy = "EXCLUDE_CLAIMED";
        var integration = new CombinedProtectionIntegration(List.of(
            new Claim("towny", Set.of(0, 1)), new Claim("worldguard", Set.of(1))), () -> config);

        var chunks = integration.resolveEffectiveChunks(WORLD, 0, 2, 0, 0, config.defaultPolicy());
        assertEquals(List.of(0, 2), chunks.stream().map(chunk -> chunk[0]).toList());
        assertTrue(integration.hasClaim(WORLD, 0, 0));
    }

    @Test
    void abortTakesPrecedenceOverExclusionRegardlessOfProviderOrder() {
        var config = new PluginConfig.LandProtection();
        config.providers.towny.structurePolicy = "EXCLUDE_CLAIMED";
        config.providers.worldguard.structurePolicy = "ABORT_WHOLE";
        var integration = new CombinedProtectionIntegration(List.of(
            new Claim("towny", Set.of(1)), new Claim("worldguard", Set.of(1))), () -> config);
        assertNull(integration.resolveEffectiveChunks(WORLD, 0, 2, 0, 0, config.defaultPolicy()));
    }

    @Test
    void providerPolicyChangesTakeEffectWithoutRebuildingIntegration() {
        var config = new PluginConfig.LandProtection();
        var integration = new CombinedProtectionIntegration(List.of(new Claim("towny", Set.of(1))), () -> config);
        assertEquals(2, integration.resolveEffectiveChunks(WORLD, 0, 2, 0, 0, config.defaultPolicy()).size());
        config.providers.towny.structurePolicy = "IGNORE_CLAIMS";
        assertEquals(3, integration.resolveEffectiveChunks(WORLD, 0, 2, 0, 0, config.defaultPolicy()).size());
        assertTrue(integration.hasClaim(WORLD, 1, 0));
    }

    @Test
    void disabledPluginDoesNotAppearUnclaimed() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> false;
                case "getName" -> "Towny";
                default -> null;
            });
        var integration = new GuardedProtectionIntegration(owner, new Claim("towny", Set.of()));
        assertThrows(IllegalStateException.class, () -> integration.hasClaim(WORLD, 1, 0));
    }

    private record Claim(String id, Set<Integer> chunks) implements ProtectionIntegration, LandProtection, ProtectionProvider {
        @Override public LandProtection landProtection() { return this; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean hasClaim(World world, int x, int z) { return chunks.contains(x); }
        @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) { throw new UnsupportedOperationException(); }
        @Override public List<int[]> resolveEffectiveChunks(String world, int minX, int maxX, int minZ, int maxZ,
                PluginConfig.Structure.PartialClaimPolicy policy) { throw new UnsupportedOperationException(); }
    }
}

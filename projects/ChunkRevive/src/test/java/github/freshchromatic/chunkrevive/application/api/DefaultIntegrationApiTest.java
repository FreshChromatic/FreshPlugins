package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.integration.ProtectionChange;
import github.freshchromatic.chunkrevive.api.integration.ProtectionChangeType;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultIntegrationApiTest {
    @Test
    void disabledBuiltInProviderContinuesToDenyMaintenance() {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> enabled.get();
                case "getName" -> "Example";
                case "equals" -> proxy == args[0];
                default -> null;
            });
        var api = new DefaultIntegrationApi();
        api.builtInRegistrar().registerProtectionProvider(owner, new ProtectionProvider() {
            @Override public String id() { return "built-in"; }
            @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
                return CompletableFuture.completedFuture(new ProtectionBatchResult(Map.of(), Map.of()));
            }
        });
        enabled.set(false);
        api.ownerDisabled(owner);
        var chunk = new ChunkKey("world", 1, 2);

        var result = api.check(MaintenanceAction.DELETE_CHUNK, List.of(chunk)).toCompletableFuture().join();
        assertEquals(ProtectionDecision.DENY, result.decisions().get(chunk));
        assertFalse(api.activeIntegrations().iterator().next().active());
    }

    @Test
    void synchronousProviderFailureDeniesTheAffectedChunk() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Example";
                default -> null;
            });
        var api = new DefaultIntegrationApi();
        api.registerProtectionProvider(owner, new ProtectionProvider() {
            @Override public String id() { return "example"; }
            @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
                throw new IllegalStateException("unavailable");
            }
        });
        var chunk = new ChunkKey("world", 1, 2);
        var result = api.check(MaintenanceAction.DELETE_CHUNK, List.of(chunk)).toCompletableFuture().join();
        assertEquals(ProtectionDecision.DENY, result.decisions().get(chunk));
        assertEquals("PROVIDER_UNAVAILABLE:example", result.reasonCodes().get(chunk));
    }

    @Test
    void asynchronousProviderCanDenyWithoutBlockingTheCaller() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Example";
                default -> null;
            });
        var pending = new CompletableFuture<ProtectionBatchResult>();
        var api = new DefaultIntegrationApi();
        api.registerProtectionProvider(owner, new ProtectionProvider() {
            @Override public String id() { return "example"; }
            @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) { return pending; }
        });
        var chunk = new ChunkKey("world", 1, 2);
        var result = api.check(MaintenanceAction.MARK, List.of(chunk)).toCompletableFuture();
        assertFalse(result.isDone());
        pending.complete(new ProtectionBatchResult(Map.of(chunk, ProtectionDecision.DENY), Map.of()));
        assertEquals(ProtectionDecision.DENY, result.join().decisions().get(chunk));
    }

    @Test
    void changesDuringAnAsynchronousCheckInvalidateItsResult() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Example";
                default -> null;
            });
        var pending = new CompletableFuture<ProtectionBatchResult>();
        var api = new DefaultIntegrationApi();
        var registration = api.registerProtectionProvider(owner, new ProtectionProvider() {
            @Override public String id() { return "example"; }
            @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) { return pending; }
        });
        var chunk = new ChunkKey("world", 1, 2);
        var result = api.check(MaintenanceAction.MARK, List.of(chunk)).toCompletableFuture();
        registration.notifyChanged(new ProtectionChange(ProtectionChangeType.FULL_RESCAN_REQUIRED, Optional.empty()));
        pending.complete(new ProtectionBatchResult(Map.of(), Map.of()));
        assertEquals(ProtectionDecision.DENY, result.join().decisions().get(chunk));
        assertEquals("PROTECTION_CHANGED", result.join().reasonCodes().get(chunk));
    }

    @Test
    void externalOnlyCheckDoesNotOverrideBuiltInStructurePolicies() {
        Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "Example";
                default -> null;
            });
        var api = new DefaultIntegrationApi();
        var chunk = new ChunkKey("world", 1, 2);
        api.builtInRegistrar().registerProtectionProvider(owner, new ProtectionProvider() {
            @Override public String id() { return "built-in"; }
            @Override public CompletionStage<ProtectionBatchResult> check(ProtectionQuery query) {
                return CompletableFuture.completedFuture(new ProtectionBatchResult(
                    Map.of(chunk, ProtectionDecision.DENY), Map.of()));
            }
        });
        assertEquals(ProtectionDecision.DENY,
            api.check(MaintenanceAction.MARK, List.of(chunk)).toCompletableFuture().join().decisions().get(chunk));
        assertEquals(ProtectionDecision.ABSTAIN,
            api.checkExternal(MaintenanceAction.MARK, List.of(chunk)).toCompletableFuture().join()
                .decisions().getOrDefault(chunk, ProtectionDecision.ABSTAIN));
    }
}

package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.integration.*;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Map;
import java.util.HashMap;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/** Owns external providers and removes them as soon as their owning plugin stops. */
public final class DefaultIntegrationApi implements IntegrationApi, Listener {
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<ProtectionChange> changeListener = ignored -> {};
    void setChangeListener(java.util.function.Consumer<ProtectionChange> listener) { changeListener = listener == null ? ignored -> {} : listener; }
    @Override public ProtectionRegistration registerProtectionProvider(Plugin owner, ProtectionProvider provider) {
        if (!owner.isEnabled()) throw new IllegalStateException("Provider owner is disabled");
        String key = owner.getName() + ':' + provider.id();
        Registration registration = new Registration(key, owner, provider);
        if (registrations.putIfAbsent(key, registration) != null) throw new IllegalArgumentException("Provider already registered: " + provider.id());
        return registration;
    }
    @Override public Collection<IntegrationSnapshot> activeIntegrations() {
        return registrations.values().stream().map(r -> new IntegrationSnapshot(r.provider.id(), r.owner.getName(), !r.closed, Optional.empty())).toList();
    }
    /** Invokes every provider once for the full batch. A timeout or failure fails closed. */
    CompletionStage<ProtectionBatchResult> check(MaintenanceAction action, Collection<ChunkKey> chunks) {
        List<Registration> providers = registrations.values().stream().filter(registration -> !registration.closed).toList();
        if (providers.isEmpty()) return CompletableFuture.completedFuture(new ProtectionBatchResult(Map.of(), Map.of()));
        List<CompletableFuture<ProtectionBatchResult>> futures = providers.stream().map(registration ->
            registration.provider.check(new ProtectionQuery(action, chunks)).toCompletableFuture().orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(failure -> denied(chunks, "PROVIDER_UNAVAILABLE:" + registration.provider.id()))).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> merge(futures));
    }
    private static ProtectionBatchResult merge(List<CompletableFuture<ProtectionBatchResult>> results) {
        Map<ChunkKey, ProtectionDecision> decisions = new HashMap<>(); Map<ChunkKey, String> reasons = new HashMap<>();
        for (ProtectionBatchResult result : results.stream().map(CompletableFuture::join).toList()) {
            for (var entry : result.decisions().entrySet()) {
                ProtectionDecision current = decisions.getOrDefault(entry.getKey(), ProtectionDecision.ABSTAIN);
                ProtectionDecision next = entry.getValue() == null ? ProtectionDecision.ABSTAIN : entry.getValue();
                if (current != ProtectionDecision.DENY && next == ProtectionDecision.DENY) {
                    decisions.put(entry.getKey(), ProtectionDecision.DENY);
                    reasons.put(entry.getKey(), result.reasonCodes().getOrDefault(entry.getKey(), "PROTECTION_BLOCKED"));
                } else if (current == ProtectionDecision.ABSTAIN && next == ProtectionDecision.ALLOW) decisions.put(entry.getKey(), ProtectionDecision.ALLOW);
            }
        }
        return new ProtectionBatchResult(decisions, reasons);
    }
    private static ProtectionBatchResult denied(Collection<ChunkKey> chunks, String reason) {
        Map<ChunkKey, ProtectionDecision> decisions = new HashMap<>(); Map<ChunkKey, String> reasons = new HashMap<>();
        for (ChunkKey chunk : chunks) { decisions.put(chunk, ProtectionDecision.DENY); reasons.put(chunk, reason); }
        return new ProtectionBatchResult(decisions, reasons);
    }
    @EventHandler public void onPluginDisable(PluginDisableEvent event) {
        registrations.values().removeIf(registration -> {
            if (!registration.owner.equals(event.getPlugin())) return false;
            registration.close();
            return true;
        });
    }
    private final class Registration implements ProtectionRegistration {
        private final String key;
        private final Plugin owner;
        private final ProtectionProvider provider;
        private volatile boolean closed;

        Registration(String key, Plugin owner, ProtectionProvider provider) {
            this.key = key;
            this.owner = owner;
            this.provider = provider;
        }
        @Override public String providerId() { return provider.id(); }
        @Override public void notifyChanged(ProtectionChange change) { if (!closed) changeListener.accept(change); }
        @Override public void close() { closed = true; registrations.remove(key, this); }
    }
}

package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.integration.IntegrationSnapshot;
import github.freshchromatic.chunkrevive.api.integration.MaintenanceAction;
import github.freshchromatic.chunkrevive.api.integration.ProtectionBatchResult;
import github.freshchromatic.chunkrevive.api.integration.ProtectionChange;
import github.freshchromatic.chunkrevive.api.integration.ProtectionDecision;
import github.freshchromatic.chunkrevive.api.integration.ProtectionProvider;
import github.freshchromatic.chunkrevive.api.integration.ProtectionQuery;
import github.freshchromatic.chunkrevive.api.integration.ProtectionRegistration;
import github.freshchromatic.chunkrevive.api.model.ChunkKey;
import github.freshchromatic.chunkrevive.integration.protection.ProtectionChecks;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class DefaultIntegrationApi implements IntegrationApi, Listener, ProtectionChecks {
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final AtomicLong                              revision      = new AtomicLong();

    private volatile Consumer<ProtectionChange> changeListener = ignored -> {};

    void setChangeListener(Consumer<ProtectionChange> listener) {
        changeListener = listener == null ? ignored -> {} : listener;
    }

    @Override
    public ProtectionRegistration registerProtectionProvider(Plugin owner, ProtectionProvider provider) {
        return register(owner, provider, false);
    }
    public IntegrationApi builtInRegistrar() {
        return new IntegrationApi() {
            @Override
            public ProtectionRegistration registerProtectionProvider(Plugin owner, ProtectionProvider provider) {
                return register(owner, provider, true);
            }
            @Override
            public Collection<IntegrationSnapshot> activeIntegrations() {
                return DefaultIntegrationApi.this.activeIntegrations();
            }
        };
    }
    private ProtectionRegistration register(Plugin owner, ProtectionProvider provider, boolean builtIn) {
        if (!owner.isEnabled()) {
            throw new IllegalStateException("Provider owner is disabled");
        }
        String key = owner.getName() + ':' + provider.id();
        Registration registration = new Registration(key, owner, provider, builtIn);
        if (registrations.putIfAbsent(key, registration) != null) {
            throw new IllegalArgumentException("Provider already registered: " + provider.id());
        }
        revision.incrementAndGet();
        return registration;
    }
    @Override
    public long revision() {
        return revision.get();
    }

    void protectionSettingsChanged() {
        revision.incrementAndGet();
    }

    @Override
    public Collection<IntegrationSnapshot> activeIntegrations() {
        return registrations.values().stream().map(r -> new IntegrationSnapshot(
            r.provider.id(), r.owner.getName(), !r.closed && r.owner.isEnabled(), Optional.empty())).toList();
    }
    @Override
    public CompletionStage<ProtectionBatchResult> check(MaintenanceAction action, Collection<ChunkKey> chunks) {
        return check(action, chunks, false);
    }
    @Override
    public CompletionStage<ProtectionBatchResult> checkExternal(MaintenanceAction action, Collection<ChunkKey> chunks) {
        return check(action, chunks, true);
    }
    private CompletionStage<ProtectionBatchResult> check(MaintenanceAction action, Collection<ChunkKey> chunks,
                                                          boolean externalOnly) {
        long checkedRevision = revision.get();
        List<Registration> providers = registrations.values().stream()
            .filter(registration -> !registration.closed && (!externalOnly || !registration.builtIn)).toList();
        if (providers.isEmpty()) {
            return CompletableFuture.completedFuture(new ProtectionBatchResult(Map.of(), Map.of()));
        }
        List<CompletableFuture<ProtectionBatchResult>> futures = providers.stream()
            .map(registration -> checkProvider(registration, action, chunks)).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored ->
            checkedRevision == revision.get() ? merge(futures) : denied(chunks, "PROTECTION_CHANGED"));
    }

    private CompletableFuture<ProtectionBatchResult> checkProvider(Registration registration, MaintenanceAction action,
                                                                    Collection<ChunkKey> chunks) {
        String reason = "PROVIDER_UNAVAILABLE:" + registration.provider.id();
        try {
            if (!registration.owner.isEnabled()) {
                return CompletableFuture.completedFuture(denied(chunks, reason));
            }
            return registration.provider.check(new ProtectionQuery(action, chunks)).toCompletableFuture()
                .orTimeout(3, TimeUnit.SECONDS)
                .thenApply(result -> registration.closed || !registration.owner.isEnabled() || result == null
                    ? denied(chunks, reason) : result)
                .exceptionally(failure -> denied(chunks, reason));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(denied(chunks, reason));
        }
    }
    private static ProtectionBatchResult merge(List<CompletableFuture<ProtectionBatchResult>> results) {
        Map<ChunkKey, ProtectionDecision> decisions = new HashMap<>();
        Map<ChunkKey, String> reasons = new HashMap<>();
        for (ProtectionBatchResult result : results.stream().map(CompletableFuture::join).toList()) {
            for (var entry : result.decisions().entrySet()) {
                ProtectionDecision current = decisions.getOrDefault(entry.getKey(), ProtectionDecision.ABSTAIN);
                ProtectionDecision next = entry.getValue() == null ? ProtectionDecision.ABSTAIN : entry.getValue();
                if (current != ProtectionDecision.DENY && next == ProtectionDecision.DENY) {
                    decisions.put(entry.getKey(), ProtectionDecision.DENY);
                    reasons.put(entry.getKey(), result.reasonCodes().getOrDefault(entry.getKey(), "PROTECTION_BLOCKED"));
                } else if (current == ProtectionDecision.ABSTAIN && next == ProtectionDecision.ALLOW) {
                    decisions.put(entry.getKey(), ProtectionDecision.ALLOW);
                }
            }
        }
        return new ProtectionBatchResult(decisions, reasons);
    }
    private static ProtectionBatchResult denied(Collection<ChunkKey> chunks, String reason) {
        Map<ChunkKey, ProtectionDecision> decisions = new HashMap<>();
        Map<ChunkKey, String> reasons = new HashMap<>();
        for (ChunkKey chunk : chunks) {
            decisions.put(chunk, ProtectionDecision.DENY);
            reasons.put(chunk, reason);
        }
        return new ProtectionBatchResult(decisions, reasons);
    }
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        ownerDisabled(event.getPlugin());
    }
    void ownerDisabled(Plugin owner) {
        registrations.values().forEach(registration -> {
            if (!registration.owner.equals(owner)) return;
            if (registration.builtIn) {
                revision.incrementAndGet();
            } else {
                registration.close();
            }
        });
    }
    private final class Registration implements ProtectionRegistration {
        private final String             key;
        private final Plugin             owner;
        private final ProtectionProvider provider;
        private final boolean            builtIn;

        private volatile boolean closed;

        Registration(String key, Plugin owner, ProtectionProvider provider, boolean builtIn) {
            this.key = key;
            this.owner = owner;
            this.provider = provider;
            this.builtIn = builtIn;
        }
        @Override
        public String providerId() {
            return provider.id();
        }

        @Override
        public void notifyChanged(ProtectionChange change) {
            if (!closed) {
                revision.incrementAndGet();
                changeListener.accept(change);
            }
        }
        @Override
        public void close() {
            if (registrations.remove(key, this)) {
                closed = true;
                revision.incrementAndGet();
            }
        }
    }
}

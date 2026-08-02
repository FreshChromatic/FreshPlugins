package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.*;
import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.mark.MarkApi;
import github.freshchromatic.chunkrevive.api.operation.OperationApi;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import org.bukkit.plugin.Plugin;

public final class DefaultChunkReviveApi implements ChunkReviveApi {
    private final Plugin plugin;
    private final MarkRegistry marks;
    private final WorldAccessPolicy worlds;
    private final DefaultIntegrationApi integrations;
    private final StructureService structures;
    private final OperationCoordinator operationCoordinator;
    private final WorldGeneratorCompatibilityApi generators = new DefaultWorldGeneratorCompatibilityApi();
    private volatile boolean active = true;
    public DefaultChunkReviveApi(Plugin plugin, MarkRegistry marks, WorldAccessPolicy worlds, ResetService resets,
                                 DeletionService deletions, ChunkScanService scans, StructureService structures,
                                 DefaultIntegrationApi integrations, OperationStore operationStore) {
        this.plugin = plugin; this.marks = marks; this.worlds = worlds; this.structures = structures; this.integrations = integrations;
        this.operationCoordinator = new OperationCoordinator(
            plugin, marks, resets, deletions, scans, worlds, generators, integrations, operationStore);
        integrations.setChangeListener(change -> operationCoordinator.invalidatePreviews());
    }
    @Override public ApiVersion apiVersion() { return ApiVersion.CURRENT; }
    @Override public Capabilities capabilities() { return new DefaultCapabilities(); }
    @Override public ChunkReviveClient client(Plugin consumer) {
        if (!active || !consumer.isEnabled()) throw new IllegalStateException("ChunkRevive API is unavailable");
        MarkApi markApi = new DefaultMarkApi(plugin, consumer, marks, worlds);
        OperationApi operations = new DefaultOperationApi(consumer, operationCoordinator);
        var structureApi = new DefaultStructureApi(plugin, consumer, structures, marks, operationCoordinator);
        return new ChunkReviveClient() {
            @Override public MarkApi marks() {
                return markApi;
            }

            @Override public OperationApi operations() {
                return operations;
            }

            @Override public github.freshchromatic.chunkrevive.api.structure.StructureApi structures() {
                return structureApi;
            }
        };
    }
    @Override public IntegrationApi integrations() { return integrations; }
    @Override public WorldGeneratorCompatibilityApi worldGenerators() { return generators; }
    public void deactivate() { active = false; operationCoordinator.deactivate(); }
}

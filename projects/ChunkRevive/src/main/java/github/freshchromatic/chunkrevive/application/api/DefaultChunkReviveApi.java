package github.freshchromatic.chunkrevive.application.api;

import github.freshchromatic.chunkrevive.api.ApiVersion;
import github.freshchromatic.chunkrevive.api.Capabilities;
import github.freshchromatic.chunkrevive.api.ChunkReviveApi;
import github.freshchromatic.chunkrevive.api.ChunkReviveClient;
import github.freshchromatic.chunkrevive.api.integration.IntegrationApi;
import github.freshchromatic.chunkrevive.api.mark.MarkApi;
import github.freshchromatic.chunkrevive.api.operation.OperationApi;
import github.freshchromatic.chunkrevive.api.structure.StructureApi;
import github.freshchromatic.chunkrevive.api.worldgen.WorldGeneratorCompatibilityApi;
import github.freshchromatic.chunkrevive.config.WorldAccessPolicy;
import github.freshchromatic.chunkrevive.feature.marking.MarkRegistry;
import github.freshchromatic.chunkrevive.feature.marking.ProtectedMarkExecutor;
import github.freshchromatic.chunkrevive.feature.operation.OperationStore;
import github.freshchromatic.chunkrevive.feature.reset.DeletionService;
import github.freshchromatic.chunkrevive.feature.reset.ResetService;
import github.freshchromatic.chunkrevive.feature.scanning.ChunkScanService;
import github.freshchromatic.chunkrevive.feature.structure.StructureService;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class DefaultChunkReviveApi implements ChunkReviveApi {

    private final Plugin                                plugin;
    private final MarkRegistry                          marks;
    private final ProtectedMarkExecutor                 protectedMarks;
    private final WorldAccessPolicy                     worlds;
    private final DefaultIntegrationApi                 integrations;
    private final StructureService                      structures;
    private final OperationCoordinator                  operationCoordinator;
    private final DefaultWorldGeneratorCompatibilityApi generators = new DefaultWorldGeneratorCompatibilityApi();
    private volatile boolean                            active     = true;

    public DefaultChunkReviveApi(Plugin plugin, MarkRegistry marks, WorldAccessPolicy worlds, ResetService resets,
        DeletionService deletions, ChunkScanService scans, StructureService structures,
        DefaultIntegrationApi integrations, OperationStore operationStore, ProtectedMarkExecutor protectedMarks) {
        this.plugin = plugin;
        this.marks = marks;
        this.worlds = worlds;
        this.structures = structures;
        this.integrations = integrations;
        this.protectedMarks = protectedMarks;
        this.operationCoordinator = new OperationCoordinator(plugin, marks, resets, deletions, scans, worlds,
            generators, integrations, operationStore);
        generators.start(plugin);
        integrations.setChangeListener(change -> operationCoordinator.invalidatePreviews());
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.CURRENT;
    }

    @Override
    public Capabilities capabilities() {
        return new DefaultCapabilities();
    }

    @Override
    public ChunkReviveClient client(Plugin consumer) {
        if (!active || !consumer.isEnabled()) {
            throw new IllegalStateException("ChunkRevive API is unavailable");
        }
        MarkApi markApi = new DefaultMarkApi(plugin, consumer, marks, worlds, protectedMarks);
        OperationApi operations = new DefaultOperationApi(consumer, operationCoordinator);
        var structureApi = new DefaultStructureApi(plugin, consumer, structures, marks, operationCoordinator);
        return new ChunkReviveClient() {

            @Override
            public MarkApi marks() {
                return markApi;
            }

            @Override
            public OperationApi operations() {
                return operations;
            }

            @Override
            public StructureApi structures() {
                return structureApi;
            }
        };
    }

    @Override
    public IntegrationApi integrations() {
        return integrations;
    }

    @Override
    public WorldGeneratorCompatibilityApi worldGenerators() {
        return generators;
    }

    public void protectionSettingsChanged() {
        integrations.protectionSettingsChanged();
        operationCoordinator.invalidatePreviews();
    }

    public void deactivate() {
        active = false;
        generators.stop();
        operationCoordinator.deactivate();
    }

    public boolean stopPersistence(Duration timeout) {
        return operationCoordinator.stopPersistence(timeout);
    }

    public void deletionJobsCancelled(List<DeletionService.CancelledDeletionJob> jobs) {
        operationCoordinator.deletionJobsCancelled(jobs);
    }
}

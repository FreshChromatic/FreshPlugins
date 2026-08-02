package github.freshchromatic.chunkrevive.config;

import github.freshchromatic.chunkrevive.config.PluginConfig;

public final class WorldAccessPolicy {

    public enum Scope { MANUAL_MARK, BULK_MARK, STRUCTURE_AUTO_DETECT, STRUCTURE_REFRESH, REGEN }

    private PluginConfig config;

    public WorldAccessPolicy(PluginConfig config) {
        this.config = config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    public boolean isAllowed(String worldName, Scope scope) {
        var cfg = config.worlds;
        boolean guarded = switch (scope) {
            case MANUAL_MARK -> cfg.scope.manualMark;
            case BULK_MARK -> cfg.scope.bulkMark;
            case STRUCTURE_AUTO_DETECT -> cfg.scope.structureAutoDetect;
            case STRUCTURE_REFRESH -> cfg.scope.structureRefresh;
            case REGEN -> cfg.scope.regen;
        };
        if (!guarded) return true;

        boolean inList = cfg.list.contains(worldName);
        return cfg.modeEnum() == PluginConfig.Worlds.ListMode.WHITELIST ? inList : !inList;
    }
}

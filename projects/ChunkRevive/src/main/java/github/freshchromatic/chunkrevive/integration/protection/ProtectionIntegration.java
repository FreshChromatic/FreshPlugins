package github.freshchromatic.chunkrevive.integration.protection;

import github.freshchromatic.chunkrevive.feature.marking.MarkService;
import org.bukkit.plugin.Plugin;

/** Optional claim-plugin integration including its event-listener registration. */
public interface ProtectionIntegration {
    LandProtection landProtection();

    default void registerListeners(Plugin plugin, MarkService markService) {
        // Null-object integrations have no listeners.
    }
}

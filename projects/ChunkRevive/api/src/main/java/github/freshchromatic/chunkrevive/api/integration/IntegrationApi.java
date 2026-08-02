package github.freshchromatic.chunkrevive.api.integration;
import java.util.Collection;
import org.bukkit.plugin.Plugin;
public interface IntegrationApi {
    ProtectionRegistration registerProtectionProvider(Plugin owner, ProtectionProvider provider);
    Collection<IntegrationSnapshot> activeIntegrations();
}

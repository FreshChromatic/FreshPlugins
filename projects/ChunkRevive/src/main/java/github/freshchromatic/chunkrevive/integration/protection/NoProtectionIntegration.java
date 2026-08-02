package github.freshchromatic.chunkrevive.integration.protection;

/** Disabled integration used when no supported claim plugin is installed. */
public final class NoProtectionIntegration implements ProtectionIntegration {
    private final LandProtection landProtection = new NoLandProtection();

    @Override
    public LandProtection landProtection() {
        return landProtection;
    }
}

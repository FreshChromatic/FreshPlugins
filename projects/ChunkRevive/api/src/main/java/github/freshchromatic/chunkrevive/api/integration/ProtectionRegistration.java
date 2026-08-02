package github.freshchromatic.chunkrevive.api.integration;
public interface ProtectionRegistration extends AutoCloseable {
    String providerId();

    void notifyChanged(ProtectionChange change);

    @Override
    void close();
}

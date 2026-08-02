package github.freshchromatic.chunkrevive.api.integration;
import java.util.concurrent.CompletionStage;
public interface ProtectionProvider { String id(); CompletionStage<ProtectionBatchResult> check(ProtectionQuery query); }

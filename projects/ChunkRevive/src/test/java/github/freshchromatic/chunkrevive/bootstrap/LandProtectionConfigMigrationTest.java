package github.freshchromatic.chunkrevive.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandProtectionConfigMigrationTest {
    @Test
    void legacyValuesSurviveMigrationAndReload(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
            scan:
              check-residence-claims: false
            structure:
              residence:
                on-partial-claim: ABORT_WHOLE
            """);
        var manager = new ChunkReviveConfigManager(path, "test");
        manager.load();
        assertFalse(manager.config().landProtection.scanClaims);
        assertEquals("ABORT_WHOLE", manager.config().landProtection.defaultStructurePolicy);
        assertTrue(manager.config().landProtection.providers.bonfire.enabled);
        manager.load();
        assertFalse(manager.config().landProtection.scanClaims);
        assertEquals("ABORT_WHOLE", manager.config().landProtection.defaultStructurePolicy);
        String saved = Files.readString(path);
        assertFalse(saved.contains("check-residence-claims:"));
        assertFalse(saved.contains("on-partial-claim:"));
        assertTrue(saved.contains("land-protection:"));
    }

    @Test
    void newValuesWinOverLegacyValues(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, """
            scan:
              check-residence-claims: false
            structure:
              residence:
                on-partial-claim: ABORT_WHOLE
            land-protection:
              scan-claims: true
              default-structure-policy: IGNORE_CLAIMS
              providers:
                towny:
                  enabled: false
                  structure-policy: EXCLUDE_CLAIMED
            """);
        var manager = new ChunkReviveConfigManager(path, "test");
        manager.load();
        assertTrue(manager.config().landProtection.scanClaims);
        assertEquals("IGNORE_CLAIMS", manager.config().landProtection.defaultStructurePolicy);
        assertFalse(manager.config().landProtection.providers.towny.enabled);
        assertEquals("EXCLUDE_CLAIMED", manager.config().landProtection.providers.towny.structurePolicy);
    }
}

package github.freshchromatic.freshlib.config.configurate;

import github.freshchromatic.freshlib.config.configurate.cache.ConfigCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigCacheTest {

    @Test
    void saveFailureIsReportedAndKeepsCachedValue(@TempDir Path directory) {
        var failure = new IllegalStateException("save failed");
        var cache = new ConfigCache<String>(directory.resolve("config.yml"), ignored -> {
        }, ignored -> {
            throw failure;
        });
        String value = "current configuration";
        cache.set(value);

        assertSame(failure, assertThrows(IllegalStateException.class, cache::save));
        assertSame(value, cache.get());
    }
}

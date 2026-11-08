package github.freshchromatic.freshlib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UUIDFetcherTest {

    @Test
    void rejectsNamesOutsideMinecraftUsernameFormatBeforeConnecting() {
        assertNull(UUIDFetcher.getUUIDAt(null, 0));
        assertNull(UUIDFetcher.getUUIDAt("", 0));
        assertNull(UUIDFetcher.getUUIDAt("player/name", 0));
        assertNull(UUIDFetcher.getUUIDAt("player name", 0));
        assertNull(UUIDFetcher.getUUIDAt("12345678901234567", 0));
    }

    @Test
    void returnsNullForMissingUuid() {
        assertNull(UUIDFetcher.getName(null));
    }

    @Test
    void historicalCacheKeyIncludesApiTimestampPrecision() {
        assertEquals(UUIDFetcher.lookupKey("player", 1_000), UUIDFetcher.lookupKey("player", 1_999));
        assertNotEquals(UUIDFetcher.lookupKey("player", 1_000), UUIDFetcher.lookupKey("player", 2_000));
        assertNotEquals(UUIDFetcher.lookupKey("player", 1_000), UUIDFetcher.lookupKey("another", 1_000));
    }
}

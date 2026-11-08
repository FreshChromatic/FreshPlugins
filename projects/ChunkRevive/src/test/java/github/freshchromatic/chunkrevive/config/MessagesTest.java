package github.freshchromatic.chunkrevive.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void statusRegionProgressHasDefaultText() {
        assertEquals("Regions Scanned", new Messages().text("status-region-progress"));
    }
}

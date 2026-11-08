package github.freshchromatic.chunkrevive.presentation.command;

import github.freshchromatic.chunkrevive.feature.marking.MarkedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationManagerTest {

    @Test
    void previewPayloadRemainsAvailableUntilConfirmationIsConsumed() {
        var confirmations = new ConfirmationManager();
        String preview = "chunk-a,chunk-b";

        confirmations.request("sender", "reset-here-biome:world", 30, preview);

        assertEquals(preview, confirmations.peekPayload("sender", "reset-here-biome:world",
            String.class).orElseThrow());
        assertEquals(preview, confirmations.peekPayload("sender", "reset-here-biome:world",
            String.class).orElseThrow());
        assertTrue(confirmations.confirm("sender", "reset-here-biome:world"));
        assertTrue(confirmations.peekPayload("sender", "reset-here-biome:world", String.class).isEmpty());
        assertFalse(confirmations.confirm("sender", "reset-here-biome:world"));
    }

    @Test
    void mismatchedCommandCannotReusePreviewPayload() {
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "reset-here-biome:world", 30, "chunk");

        assertTrue(confirmations.peekPayload("sender", "another-command", String.class).isEmpty());
        assertFalse(confirmations.confirm("sender", "reset-here-biome:world"));
    }

    @Test
    void expiredConfirmationCannotBeConsumed() {
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "resetmark:world", -1);

        assertFalse(confirmations.confirm("sender", "resetmark:world"));
    }

    @Test
    void payloadTypeIsValidatedWithoutConsumingConfirmation() {
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "operation", 30, "preview");
        assertTrue(confirmations.peekPayload("sender", "operation", Integer.class).isEmpty());
        assertEquals("preview", confirmations.peekPayload("sender", "operation", String.class).orElseThrow());
        assertTrue(confirmations.confirm("sender", "operation"));
    }

    @Test
    void consumePayloadReturnsAndRemovesMatchingPreview() {
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "operation", 30, "preview");

        assertTrue(confirmations.consumePayload("sender", "operation", Integer.class).isEmpty());
        assertEquals("preview", confirmations.consumePayload("sender", "operation", String.class).orElseThrow());
        assertTrue(confirmations.consumePayload("sender", "operation", String.class).isEmpty());
    }

    @Test
    void biomePreviewRetainsOriginalChunkSelection() {
        var chunks = new ArrayList<MarkedChunk>();
        chunks.add(new MarkedChunk("world", 1, 2, UUID.randomUUID(), 0));
        var preview = new BiomeSelection("minecraft:plains", chunks, false);
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "biome", 30, preview);
        chunks.clear();

        assertEquals(1, confirmations.peekPayload("sender", "biome", BiomeSelection.class)
            .orElseThrow()
            .chunks()
            .size());
    }
}

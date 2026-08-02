package github.freshchromatic.chunkrevive.presentation.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationManagerTest {

    @Test
    void previewPayloadRemainsAvailableUntilConfirmationIsConsumed() {
        var confirmations = new ConfirmationManager();
        var preview = List.of("chunk-a", "chunk-b");

        confirmations.request("sender", "reset-here-biome:world", 30, preview);

        assertEquals(preview,
            confirmations.peekPayload("sender", "reset-here-biome:world").orElseThrow());
        assertEquals(preview,
            confirmations.peekPayload("sender", "reset-here-biome:world").orElseThrow());
        assertTrue(confirmations.confirm("sender", "reset-here-biome:world"));
        assertTrue(confirmations.peekPayload("sender", "reset-here-biome:world").isEmpty());
        assertFalse(confirmations.confirm("sender", "reset-here-biome:world"));
    }

    @Test
    void mismatchedCommandCannotReusePreviewPayload() {
        var confirmations = new ConfirmationManager();
        confirmations.request("sender", "reset-here-biome:world", 30, List.of("chunk"));

        assertTrue(confirmations.peekPayload("sender", "another-command").isEmpty());
        assertFalse(confirmations.confirm("sender", "reset-here-biome:world"));
    }
}

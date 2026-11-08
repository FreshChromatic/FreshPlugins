package github.freshchromatic.chunkrevive.api.operation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenanceRequestTest {

    @Test
    void requiresAnOperationTypeAndTargetSelection() {
        var targets = new AllMarked(Optional.empty(), MarkScope.ALL);

        assertThrows(IllegalArgumentException.class, () -> new MaintenanceRequest(null, targets, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceRequest(OperationType.REGENERATE, null,
            Map.of()));
    }

    @Test
    void copiesOptionsAtConstruction() {
        var options = new HashMap<String, String>();
        options.put("strategy", "safe");
        var request = new MaintenanceRequest(OperationType.REGENERATE, new AllMarked(Optional.empty(), MarkScope.ALL),
            options);

        options.put("strategy", "unsafe");

        assertEquals(Map.of("strategy", "safe"), request.options());
        assertThrows(UnsupportedOperationException.class, () -> request.options().put("new", "value"));
    }
}

package github.freshchromatic.freshlib.duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreshDurationTest {

    @Test
    void parsesMaximumMillisecondValue() {
        assertEquals(Long.MAX_VALUE, FreshDuration.parse(Long.MAX_VALUE + "ms").millis());
    }

    @Test
    void rejectsUnitMultiplicationOverflow() {
        assertThrows(IllegalArgumentException.class, () -> FreshDuration.parse(Long.MAX_VALUE + "s"));
    }

    @Test
    void rejectsTotalDurationOverflow() {
        assertThrows(IllegalArgumentException.class, () -> FreshDuration.parse("9223372036854775807ms1ms"));
    }
}

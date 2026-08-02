package github.freshchromatic.chunkrevive.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafetyLimitPolicyTest {

    @Test
    void autoAndLegacyZeroUseCalculatedLimit() {
        assertEquals(6, SafetyLimitPolicy.resolveCap("AUTO", 6));
        assertEquals(6, SafetyLimitPolicy.resolveCap("0", 6));
    }

    @Test
    void configAndIgnoreRemoveAdditionalSafetyCap() {
        assertEquals(Integer.MAX_VALUE, SafetyLimitPolicy.resolveCap("CONFIG", 6));
        assertEquals(Integer.MAX_VALUE, SafetyLimitPolicy.resolveCap("IGNORE", 6));
        assertEquals(Integer.MAX_VALUE, SafetyLimitPolicy.resolveCap("UNLIMITED", 6));
    }

    @Test
    void positiveNumberRemainsAnExplicitCap() {
        assertEquals(12, SafetyLimitPolicy.resolveCap("12", 6));
    }
}

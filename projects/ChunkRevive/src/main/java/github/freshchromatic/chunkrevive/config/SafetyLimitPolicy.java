package github.freshchromatic.chunkrevive.config;

import java.util.Locale;

/** Parses memory-safety limit values while retaining compatibility with legacy numeric YAML values. */
public final class SafetyLimitPolicy {
    private SafetyLimitPolicy() {}

    /** Returns {@link Integer#MAX_VALUE} when the safety layer should not add a cap. */
    public static int resolveCap(String raw, int automaticValue) {
        String value = raw == null ? "AUTO" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.equals("CONFIG") || value.equals("IGNORE") || value.equals("UNLIMITED")) {
            return Integer.MAX_VALUE;
        }
        if (value.equals("AUTO") || value.isEmpty() || value.equals("0")) {
            return Math.max(1, automaticValue);
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : Math.max(1, automaticValue);
        } catch (NumberFormatException ignored) {
            return Math.max(1, automaticValue);
        }
    }
}

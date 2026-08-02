package github.freshchromatic.chunkrevive.feature.reset;

import java.util.Locale;

/** Storage operation used to reset selected chunks. */
public enum ResetMethod {
    DEFAULT,
    REGENERATE,
    DELETE_CHUNK,
    DELETE_REGION;

    public static ResetMethod parse(String raw, ResetMethod fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        normalized = switch (normalized) {
            case "REGEN", "CHUNK_REGEN", "RESET_CHUNK" -> "REGENERATE";
            case "CHUNK", "DELETECHUNK" -> "DELETE_CHUNK";
            case "REGION", "DELETEREGION" -> "DELETE_REGION";
            default -> normalized;
        };
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

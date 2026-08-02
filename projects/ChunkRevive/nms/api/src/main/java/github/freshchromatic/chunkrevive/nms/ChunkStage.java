package github.freshchromatic.chunkrevive.nms;

import java.util.Locale;

/** Ordered, version-neutral subset of persisted chunk generation stages. */
public enum ChunkStage {
    EMPTY,
    STRUCTURE_STARTS,
    STRUCTURE_REFERENCES,
    BIOMES,
    NOISE,
    SURFACE,
    CARVERS,
    FEATURES,
    LIGHT,
    SPAWN,
    FULL;

    public boolean isBefore(ChunkStage other) {
        return ordinal() < other.ordinal();
    }

    public static ChunkStage configured(String raw) {
        if (raw == null) return FULL;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return FULL;
        }
    }

    public static ChunkStage persisted(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int colon = key.indexOf(':');
        if (colon >= 0) key = key.substring(colon + 1);
        return switch (key) {
            case "full" -> FULL;
            case "heightmaps", "spawn" -> SPAWN;
            case "light" -> LIGHT;
            case "features" -> FEATURES;
            case "carvers", "liquid_carvers" -> CARVERS;
            case "surface" -> SURFACE;
            case "noise" -> NOISE;
            case "biomes" -> BIOMES;
            case "structure_references" -> STRUCTURE_REFERENCES;
            case "structure_starts" -> STRUCTURE_STARTS;
            default -> EMPTY;
        };
    }
}

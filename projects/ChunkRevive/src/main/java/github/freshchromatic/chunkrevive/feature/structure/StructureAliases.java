package github.freshchromatic.chunkrevive.feature.structure;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Same virtual-group table as DiamondDescent's StartOptionsDialog#VIRTUAL_GROUPS, copied independently to avoid a cross-module dependency. */
public final class StructureAliases {

    private static final Map<String, List<String>> VIRTUAL_GROUPS = Map.of(
        "village",       List.of("village_plains", "village_desert", "village_savanna",
                                 "village_snowy", "village_taiga"),
        "mineshaft",     List.of("mineshaft", "mineshaft_mesa"),
        "ocean_ruin",    List.of("ocean_ruin_cold", "ocean_ruin_warm"),
        "ruined_portal", List.of("ruined_portal", "ruined_portal_desert", "ruined_portal_jungle",
                                 "ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_swamp",
                                 "ruined_portal_nether")
    );

    private static final Map<String, String> REAL_TO_VIRTUAL = VIRTUAL_GROUPS.entrySet().stream()
        .flatMap(e -> e.getValue().stream().map(real -> Map.entry(real, e.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private StructureAliases() {}

    /**
     * Normalizes a real detected structure id (e.g. {@code minecraft:village_plains}) into the key
     * used for config lookups. Structures belonging to a virtual group resolve to
     * {@code minecraft:<virtual id>} (e.g. {@code minecraft:village}); everything else is returned unchanged.
     */
    public static String canonicalize(String realStructureId) {
        int colon = realStructureId.indexOf(':');
        String namespace = colon >= 0 ? realStructureId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? realStructureId.substring(colon + 1) : realStructureId;
        String virtual = REAL_TO_VIRTUAL.get(path);
        return virtual != null ? namespace + ":" + virtual : realStructureId;
    }
}

package github.freshchromatic.chunkrevive.api.structure;

import java.util.List;

public record StructurePage(int total, List<StructureSnapshot> structures) {

    public StructurePage {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        structures = List.copyOf(structures);
        if (total < structures.size()) {
            throw new IllegalArgumentException("total must be at least the number of structures in the page");
        }
    }
}

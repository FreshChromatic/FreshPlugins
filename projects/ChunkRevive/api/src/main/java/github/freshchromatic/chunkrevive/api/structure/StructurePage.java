package github.freshchromatic.chunkrevive.api.structure;
import java.util.List;
public record StructurePage(int total, List<StructureSnapshot> structures) { public StructurePage { structures = List.copyOf(structures); } }

package github.freshchromatic.chunkrevive.api.worldgen;
import java.util.Collection;
public interface WorldGeneratorCompatibilityApi { GeneratorCompatibilitySnapshot inspect(String world); Collection<GeneratorCompatibilitySnapshot> worlds(); }

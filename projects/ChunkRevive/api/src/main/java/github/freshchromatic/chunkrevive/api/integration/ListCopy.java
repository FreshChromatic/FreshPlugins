package github.freshchromatic.chunkrevive.api.integration;
import java.util.Collection;
import java.util.List;
final class ListCopy { static <T> List<T> copy(Collection<T> values) { return values == null ? List.of() : List.copyOf(values); } }

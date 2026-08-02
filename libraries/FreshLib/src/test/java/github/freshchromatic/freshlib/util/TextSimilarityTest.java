package github.freshchromatic.freshlib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TextSimilarityTest {
    @Test void returnsExpectedNormalizedScores() {
        assertEquals(1D, TextSimilarity.normalizedLevenshtein("same", "same"));
        assertEquals(0D, TextSimilarity.normalizedLevenshtein("", "value"));
        assertTrue(TextSimilarity.normalizedLevenshtein("hello world", "hello wurld") > .8D);
    }
}

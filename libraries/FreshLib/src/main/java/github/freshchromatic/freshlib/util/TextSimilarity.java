package github.freshchromatic.freshlib.util;

/** Small dependency-free text comparison helpers for moderation and matching features. */
public final class TextSimilarity {
    private TextSimilarity() { }

    /**
     * Returns normalized Levenshtein similarity from {@code 0.0} (unrelated) to {@code 1.0} (equal).
     * The implementation retains only two rows, so its memory use is O(min(n, m)).
     */
    public static double normalizedLevenshtein(String left, String right) {
        if (left.equals(right)) return 1D;
        if (left.isEmpty() || right.isEmpty()) return 0D;
        if (left.length() < right.length()) return normalizedLevenshtein(right, left);

        int[] previous = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1), substitution);
            }
            previous = current;
        }
        return 1D - (double) previous[right.length()] / left.length();
    }
}

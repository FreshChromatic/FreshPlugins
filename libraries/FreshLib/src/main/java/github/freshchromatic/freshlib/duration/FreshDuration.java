package github.freshchromatic.freshlib.duration;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record FreshDuration(long millis) {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    public static FreshDuration parse(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Input cannot be null or empty");
        }

        if (input.equalsIgnoreCase("never") || input.equalsIgnoreCase("off") || input.equalsIgnoreCase("none")) {
            return new FreshDuration(-1);
        }

        long totalMillis = 0;

        Matcher matcher = TOKEN_PATTERN.matcher(input);

        int pos = 0;

        while (matcher.find()) {
            if (matcher.start() != pos) {
                throw new IllegalArgumentException("Invalid format: " + input);
            }

            long value = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "ms" -> 1;
                case "s" -> 1_000;
                case "m" -> 60_000;
                case "h" -> 3_600_000;
                case "d" -> 86_400_000;
                default -> throw new IllegalArgumentException("Unknown unit: " + matcher.group(2));
            };
            try {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(value, multiplier));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Duration exceeds the supported range: " + input, overflow);
            }

            pos = matcher.end();
        }

        if (pos != input.length()) {
            throw new IllegalArgumentException("Invalid format: " + input);
        }

        return new FreshDuration(totalMillis);
    }

    public boolean isNever() {
        return millis <= 0;
    }

    public long seconds() {
        return millis / 1_000;
    }

    public long minutes() {
        return millis / 60_000;
    }

    public long hours() {
        return millis / 3_600_000;
    }

    public long days() {
        return millis / 86_400_000;
    }

    @Override
    public @NotNull String toString() {
        long remainingMillis = millis;

        long days = remainingMillis / 86_400_000;
        remainingMillis %= 86_400_000;

        long hours = remainingMillis / 3_600_000;
        remainingMillis %= 3_600_000;

        long minutes = remainingMillis / 60_000;
        remainingMillis %= 60_000;

        long seconds = remainingMillis / 1_000;
        remainingMillis %= 1_000;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d");
        }
        if (hours > 0) {
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m");
        }
        if (seconds > 0) {
            sb.append(seconds).append("s");
        }
        if (remainingMillis > 0) {
            sb.append(remainingMillis).append("ms");
        }

        return sb.toString();
    }
}

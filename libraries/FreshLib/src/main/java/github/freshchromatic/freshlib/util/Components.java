package github.freshchromatic.freshlib.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Components {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    // &x&R&R&G&G&B&B / §x§R§R§G§G§B§B (Mojang RGB form)
    private static final Pattern MOJANG_HEX = Pattern.compile(
            "(?i)[&§]x[&§]([0-9a-f])[&§]([0-9a-f])[&§]([0-9a-f])[&§]([0-9a-f])[&§]([0-9a-f])[&§]([0-9a-f])"
    );
    // &#RRGGBB / §#RRGGBB
    private static final Pattern LEGACY_HASH_HEX = Pattern.compile("(?i)[&§]#([0-9a-f]{6})");
    // single-char legacy codes: &a, §l, etc.
    private static final Pattern LEGACY_SIMPLE = Pattern.compile("(?i)[&§]([0-9a-fk-or])");

    private static final Map<Character, String> LEGACY_TAG_NAMES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private Components() {
    }

    /**
     * Converts legacy {@code &}/{@code §} color codes (including hex) into MiniMessage tags so that
     * strings mixing legacy codes with MiniMessage-only tags (e.g. {@code <shift>}/{@code <glyph>})
     * deserialize correctly instead of silently dropping one syntax or the other.
     */
    public static String normalizeLegacy(final String input) {
        if (input == null) return "";

        StringBuilder afterMojang = new StringBuilder();
        Matcher mojangMatcher = MOJANG_HEX.matcher(input);
        while (mojangMatcher.find()) {
            String hex = (mojangMatcher.group(1) + mojangMatcher.group(2) + mojangMatcher.group(3)
                    + mojangMatcher.group(4) + mojangMatcher.group(5) + mojangMatcher.group(6)).toLowerCase();
            mojangMatcher.appendReplacement(afterMojang, Matcher.quoteReplacement("<#" + hex + ">"));
        }
        mojangMatcher.appendTail(afterMojang);

        StringBuilder afterHex = new StringBuilder();
        Matcher hexMatcher = LEGACY_HASH_HEX.matcher(afterMojang.toString());
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(afterHex, Matcher.quoteReplacement("<#" + hexMatcher.group(1).toLowerCase() + ">"));
        }
        hexMatcher.appendTail(afterHex);

        StringBuilder afterSimple = new StringBuilder();
        Matcher simpleMatcher = LEGACY_SIMPLE.matcher(afterHex.toString());
        while (simpleMatcher.find()) {
            char code = Character.toLowerCase(simpleMatcher.group(1).charAt(0));
            String tag = LEGACY_TAG_NAMES.getOrDefault(code, "reset");
            simpleMatcher.appendReplacement(afterSimple, Matcher.quoteReplacement("<" + tag + ">"));
        }
        simpleMatcher.appendTail(afterSimple);

        return afterSimple.toString();
    }

    public static Component miniMessage(final String input, final TagResolver... resolvers) {
        String normalized = normalizeLegacy(input);
        if (resolvers.length == 0) {
            return MINI_MESSAGE.deserialize(normalized);
        }
        return MINI_MESSAGE.deserialize(normalized, resolvers);
    }

    public static TagResolver placeholder(final String key, final String value) {
        return Placeholder.parsed(key, value);
    }

    public static TagResolver placeholder(final String key, final Component value) {
        return Placeholder.component(key, value);
    }

    public static TagResolver placeholder(final String key, final Object value) {
        return Placeholder.parsed(key, String.valueOf(value));
    }

    /**
     * Highlights special characters ({@code <}, {@code >}, {@code |}) in command syntax strings.
     * Used by ExceptionHandler to format invalid-syntax messages.
     */
    public static Component highlightSpecialCharacters(final Component input, final TextColor highlightColor) {
        if (!(input instanceof TextComponent textComponent)) {
            return input;
        }
        final String content = textComponent.content();
        final TextComponent.Builder result = Component.text();
        final StringBuilder current = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            final char c = content.charAt(i);
            if (c == '<' || c == '>' || c == '|') {
                if (!current.isEmpty()) {
                    result.append(Component.text(current.toString()));
                    current.setLength(0);
                }
                result.append(Component.text(c).color(highlightColor));
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.append(Component.text(current.toString()));
        }
        return result.build();
    }
}

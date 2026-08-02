package github.freshchromatic.freshlib.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentsTest {

    @Test
    void mixedLegacyAndMiniMessageTitleSurvivesShiftAndGlyph() {
        String input = "<shift:-8><glyph:gui_menu_a><shift:-169>§0§l海風選単&f » &#1c1c1c&l首頁";

        Component component = Components.miniMessage(input);
        String reserialized = MiniMessage.miniMessage().serialize(component);

        // shift/glyph tags must survive the parse (this is the whole point of the fix:
        // these are Component-only, legacy serialization would have dropped them silently)
        assertTrue(reserialized.contains("shift:-8"), "expected first <shift> tag to survive, got: " + reserialized);
        assertTrue(reserialized.contains("glyph:gui_menu_a"), "expected <glyph> tag to survive, got: " + reserialized);
        assertTrue(reserialized.contains("shift:-169"), "expected second <shift> tag to survive, got: " + reserialized);

        // legacy codes must have been converted to MiniMessage tags, not left as literal text
        assertFalse(reserialized.contains("&f"), "legacy '&f' should have been converted: " + reserialized);
        assertFalse(reserialized.contains("§0"), "legacy section code should have been converted: " + reserialized);
        assertFalse(reserialized.contains("&#1c1c1c"), "legacy hex code should have been converted: " + reserialized);
    }

    @Test
    void plainMiniMessageStringIsUnaffected() {
        String input = "<#9DAFFF><bold>功能分類";
        Component component = Components.miniMessage(input);
        String reserialized = MiniMessage.miniMessage().serialize(component);
        assertTrue(reserialized.toLowerCase().contains("9dafff"), "plain MiniMessage input should pass through unchanged: " + reserialized);
        assertTrue(reserialized.contains("功能分類"), "plain text content should be preserved: " + reserialized);
    }
}

package github.freshchromatic.freshlib.gui.inventorygui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiPlaceholderReplacerTest {

    @Test
    void replacesLiteralPlaceholdersAndEscapesReplacementSyntax() {
        var replacer = new GuiPlaceholderReplacer();
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("world.name", "$world\\server");

        String result = replacer.replace("%world.name% / %worldXname%", replacements);

        assertEquals("$world\\server / %worldXname%", result);
    }

    @Test
    void skipsNullKeysAndUsesNullTextForNullValues() {
        var replacer = new GuiPlaceholderReplacer();
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put(null, "ignored");
        replacements.put("value", null);

        String result = replacer.replace("%value%", replacements);

        assertEquals("null", result);
    }
}

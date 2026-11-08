package github.freshchromatic.freshlib.gui.inventorygui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GuiPlaceholderReplacer {

    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    String replace(String text, Map<String, String> replacements) {
        String result = text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }

            String placeholder = "%" + key + "%";
            Pattern pattern = patterns.computeIfAbsent(placeholder, value -> Pattern.compile(value, Pattern.LITERAL));
            String replacement = entry.getValue() == null ? "null" : entry.getValue();
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }
}

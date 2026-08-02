package github.freshchromatic.chunkrevive.presentation.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class AdminTuiBuilder {

    public static final TextColor PRIMARY   = TextColor.fromHexString("#0094D5");
    public static final TextColor SECONDARY = TextColor.fromHexString("#7A7A7A");
    public static final TextColor ACTION    = TextColor.fromHexString("#FBFF8B");
    public static final TextColor SEVERE    = TextColor.fromHexString("#FF6048");
    public static final TextColor NORMAL    = TextColor.fromHexString("#8BFF7B");

    private static final Component DIVIDER =
        Component.text("                                        ")
            .color(PRIMARY)
            .decorate(TextDecoration.STRIKETHROUGH);

    private static final Component LINE_PREFIX   = Component.text("⌗ ").color(PRIMARY);
    private static final Component ACTION_PREFIX = Component.text("▸ ").color(PRIMARY);
    private static final Component ROW_SEP       = Component.text(" - ").color(SECONDARY);

    private AdminTuiBuilder() {}

    public static Component header(Component title) {
        return Component.text("__/ ").color(PRIMARY)
            .append(title)
            .append(Component.text(" \\__").color(PRIMARY));
    }

    public static Component divider() {
        return DIVIDER;
    }

    public static Component line(Component content) {
        return LINE_PREFIX.append(content);
    }

    public static Component actionBar(Component content) {
        return ACTION_PREFIX.append(content);
    }

    public static Component row(Component... parts) {
        Component result = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result = result.append(ROW_SEP);
            result = result.append(parts[i]);
        }
        return result;
    }

    public static Component actionButton(Component label, ClickEvent click, Component hover) {
        return button(ACTION, label, click, hover);
    }

    public static Component severeButton(Component label, ClickEvent click, Component hover) {
        return button(SEVERE, label, click, hover);
    }

    public static Component normalButton(Component label, ClickEvent click, Component hover) {
        return button(NORMAL, label, click, hover);
    }

    public static Component disabledButton(Component label) {
        return Component.empty().color(SECONDARY)
            .append(Component.text("["))
            .append(label)
            .append(Component.text("]"));
    }

    private static Component button(TextColor color, Component label, ClickEvent click, Component hover) {
        return Component.empty().color(color)
            .append(Component.text("["))
            .append(label)
            .append(Component.text("]"))
            .clickEvent(click)
            .hoverEvent(HoverEvent.showText(hover));
    }
}

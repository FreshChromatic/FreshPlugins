package github.freshchromatic.freshlib.gui.menu;

import github.freshchromatic.freshlib.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MenuConfig {
    private final JavaPlugin plugin;
    private final File menuFolder;
    private final Map<String, YamlConfiguration> menuConfigs = new HashMap<>();

    public MenuConfig(JavaPlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "menus"));
    }

    public MenuConfig(JavaPlugin plugin, File menuFolder) {
        this.plugin = plugin;
        this.menuFolder = menuFolder;
    }

    public void loadConfigs() {
        if (!menuFolder.exists()) {
            menuFolder.mkdirs();
        }

        menuConfigs.clear();
        File[] files = menuFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                String key = fileName.substring(0, fileName.lastIndexOf('.'));
                menuConfigs.put(key, YamlConfiguration.loadConfiguration(file));
            }
        }
    }

    public void saveDefaultMenu(String menuFileName, boolean replace) {
        File targetFile = new File(menuFolder, menuFileName);
        if (!targetFile.exists() || replace) {
            plugin.saveResource("menus/" + menuFileName, replace);
        }
    }

    public YamlConfiguration getConfig(String key) {
        return menuConfigs.getOrDefault(key, new YamlConfiguration());
    }

    public Component getTitle(String menuKey) {
        return parse(getTitleRaw(menuKey));
    }

    /**
     * Returns the unparsed MiniMessage/legacy source string for a menu's title.
     * Use this (not {@link #getTitle}) when handing a title to {@link github.freshchromatic.freshlib.gui.inventorygui.InventoryGui},
     * which deserializes it as a Component itself — round-tripping through a Component first and
     * serializing back to a legacy string would drop Component-only tags like {@code <shift>}/{@code <glyph>}.
     */
    public String getTitleRaw(String menuKey) {
        return getConfig(menuKey).getString("title", "Menu");
    }

    public String[] getLayout(String menuKey) {
        YamlConfiguration config = getConfig(menuKey);
        String layoutStr = config.getString("layout", "");
        if (!layoutStr.isBlank()) return layoutStr.split("\n");

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) return layoutStr.split("\n");

        Map<Character, List<Integer>> configuredSlots = new HashMap<>();
        int highestSlot = -1;
        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null || !item.contains("slots")) continue;
            if (key.length() != 1) {
                plugin.getLogger().warning("[FreshLib] slots requires a single-character item key: \"" + key + "\"");
                continue;
            }

            List<Integer> slots = parseSlots(item, menuKey + ".items." + key);
            if (!slots.isEmpty()) {
                configuredSlots.put(key.charAt(0), slots);
                highestSlot = Math.max(highestSlot, slots.stream().mapToInt(Integer::intValue).max().orElse(-1));
            }
        }
        if (configuredSlots.isEmpty()) return layoutStr.split("\n");

        int configuredSize = config.getInt("size", config.getInt("rows", 0) * 9);
        int requiredSize = ((highestSlot + 9) / 9) * 9;
        int size = Math.max(configuredSize, requiredSize);
        if (size <= 0) size = 9;
        if (size % 9 != 0) size = ((size + 8) / 9) * 9;

        char[] layout = new char[size];
        Arrays.fill(layout, ' ');
        for (Map.Entry<Character, List<Integer>> entry : configuredSlots.entrySet()) {
            for (int slot : entry.getValue()) {
                if (slot < 0 || slot >= size) {
                    plugin.getLogger().warning("[FreshLib] Slot " + slot + " is outside menu size " + size
                            + " for item \"" + entry.getKey() + "\"");
                    continue;
                }
                layout[slot] = entry.getKey();
            }
        }

        String[] rows = new String[size / 9];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = new String(layout, row * 9, 9);
        }
        return rows;
    }

    private List<Integer> parseSlots(ConfigurationSection section, String path) {
        List<Integer> slots = new ArrayList<>();
        Object raw = section.get("slots");
        List<?> values = raw instanceof List<?> list
                ? list
                : Arrays.asList(String.valueOf(raw).split(","));

        for (Object value : values) {
            try {
                slots.add(Integer.parseInt(String.valueOf(value).trim()));
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("[FreshLib] Invalid slot at " + path + ": \"" + value + "\"");
            }
        }
        return slots;
    }

    public ConfigurationSection getItemSection(String menuKey, char character) {
        return getConfig(menuKey).getConfigurationSection("items." + character);
    }

    public Component parse(String text) {
        if (text == null) return Component.empty();
        try {
            return Components.miniMessage(text)
                    .decoration(TextDecoration.ITALIC, false);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse mini-message: " + text);
            return Component.text(text).decoration(TextDecoration.ITALIC, false);
        }
    }

    public List<Component> parseLore(List<String> lore) {
        if (lore == null) return List.of();
        return lore.stream().map(this::parse).collect(Collectors.toList());
    }
}

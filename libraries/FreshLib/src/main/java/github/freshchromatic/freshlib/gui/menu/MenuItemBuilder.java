package github.freshchromatic.freshlib.gui.menu;

import github.freshchromatic.freshlib.gui.inventorygui.GuiElement;
import github.freshchromatic.freshlib.gui.inventorygui.StaticGuiElement;
import github.freshchromatic.freshlib.gui.inventorygui.GuiPageElement;
import github.freshchromatic.freshlib.item.ItemDataComponents;
import github.freshchromatic.freshlib.item.MaterialResolver;
import github.freshchromatic.freshlib.item.materialresolvers.HeadDatabaseMaterialResolver;
import github.freshchromatic.freshlib.item.materialresolvers.ItemsAdderMaterialResolver;
import github.freshchromatic.freshlib.item.materialresolvers.NexoMaterialResolver;
import github.freshchromatic.freshlib.item.materialresolvers.PlayerHeadMaterialResolver;
import github.freshchromatic.freshlib.item.materialresolvers.VanillaMaterialResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MenuItemBuilder {
    protected final JavaPlugin plugin;
    protected final MenuConfig menuConfig;
    private final Map<String, ElementBuilder> customBuilders = new HashMap<>();
    private final Map<String, ActionExecutor> executorHandlers = new HashMap<>();
    private final List<MaterialResolver> materialResolvers = new ArrayList<>();

    public MenuItemBuilder(JavaPlugin plugin, MenuConfig menuConfig) {
        this.plugin = plugin;
        this.menuConfig = menuConfig;
        registerDefaultExecutors();
        registerDefaultMaterialResolvers();
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public void registerType(String type, ElementBuilder builder) {
        customBuilders.put(type.toLowerCase(), builder);
    }

    /**
     * Registers a custom executor handler for a given prefix.
     * <p>
     * Executor strings in YAML follow the pattern {@code "prefix: argument"}.
     * The special value {@code "close"} (no colon) is already registered by default.
     * <p>
     * Example custom registration:
     * <pre>
     * builder.registerExecutor("sound", (click, arg) ->
     *     click.getWhoClicked().getWorld().playSound(...));
     * </pre>
     *
     * @param prefix   The prefix to match (case-insensitive), e.g. {@code "command"}.
     * @param executor The executor to invoke when the prefix is matched.
     */
    public void registerExecutor(String prefix, ActionExecutor executor) {
        executorHandlers.put(prefix.toLowerCase(), executor);
    }

    /**
     * Registers a {@link MaterialResolver} for namespaced {@code material} tags (e.g. {@code "[itemsadder] id"}).
     * Resolvers are tried in registration order; the built-in ones ({@code [mc]}, {@code head}, {@code [nexo]},
     * {@code [itemsadder]}, {@code [hdb]}) are registered first, so custom resolvers registered afterwards
     * effectively take priority for tags they also claim to support.
     */
    public void registerMaterialResolver(MaterialResolver resolver) {
        materialResolvers.add(resolver);
    }

    // -------------------------------------------------------------------------
    // Element building
    // -------------------------------------------------------------------------

    public GuiElement buildElement(String menuKey, char character) {
        ConfigurationSection section = menuConfig.getItemSection(menuKey, character);
        if (section == null) {
            return new StaticGuiElement(character, new ItemStack(Material.AIR));
        }

        // Resolve type: prefer root-level "type", fall back to "action.type"
        String type = section.getString("type", "").toLowerCase();
        if (type.isEmpty()) {
            ConfigurationSection actionSection = section.getConfigurationSection("action");
            if (actionSection != null) {
                type = actionSection.getString("type", "static").toLowerCase();
            } else {
                type = "static";
            }
        }

        ElementBuilder customBuilder = customBuilders.get(type);
        if (customBuilder != null) {
            return customBuilder.build(character, section);
        }

        return switch (type) {
            case "close" -> createCloseElement(character, section);
            case "paginate" -> createPaginateElement(character, section);
            default -> createStaticElement(character, section);
        };
    }

    // -------------------------------------------------------------------------
    // Built-in element creators
    // -------------------------------------------------------------------------

    protected GuiElement createStaticElement(char character, ConfigurationSection section) {
        ItemStack item = buildItemStack(section);

        GuiElement.Action action = buildExecutorAction(section);
        if (action != null) {
            return new StaticGuiElement(character, item, action);
        }
        return new StaticGuiElement(character, item, click -> true);
    }

    protected GuiElement createCloseElement(char character, ConfigurationSection section) {
        ItemStack item = buildItemStack(section, null, "BARRIER", "<red>Close");
        GuiElement.Action configuredAction = buildExecutorAction(section);

        return new StaticGuiElement(character, item, click -> {
            if (configuredAction != null) configuredAction.onClick(click);
            click.getGui().close();
            return true;
        });
    }

    protected GuiElement createPaginateElement(char character, ConfigurationSection section) {
        GuiPageElement.PageAction pageAction = resolvePaginateAction(section);
        ItemStack item = buildItemStack(section, null, "ARROW", "Page");

        GuiPageElement element = new GuiPageElement(character, item, pageAction);

        // Optional inactive item
        ItemStack inactiveItem = buildInactiveItem(section);
        if (inactiveItem != null) {
            element.setInactiveItem(inactiveItem);
        }

        GuiElement.Action configuredAction = buildExecutorAction(section);
        if (configuredAction != null) {
            GuiElement.Action pageActionHandler = element.getAction(null);
            element.setAction(click -> {
                boolean cancel = pageActionHandler.onClick(click);
                return configuredAction.onClick(click) || cancel;
            });
        }

        return element;
    }

    // -------------------------------------------------------------------------
    // Executor system
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code action.executor} list from a section and returns a combined
     * {@link GuiElement.Action} that runs all matching executors in order, or
     * {@code null} if no executors are configured.
     */
    protected GuiElement.Action buildExecutorAction(ConfigurationSection section) {
        ConfigurationSection actionSection = section.getConfigurationSection("action");
        List<String> executors = actionSection == null
                ? List.of()
                : actionSection.getStringList("executor");
        List<String> commands = section.getStringList("commands");
        if (executors.isEmpty() && commands.isEmpty()) return null;

        return click -> {
            for (String entry : executors) {
                dispatchExecutor(click, entry.trim());
            }
            for (String command : commands) {
                dispatchLegacyCommand(click, command.trim());
            }
            return true;
        };
    }

    /**
     * Executes the legacy {@code commands} syntax used by several FreshChromatic menu configs.
     * Supported prefixes are {@code msg=}, {@code console=}, {@code sound=}, {@code stopsound=}
     * and the click guards {@code left=}, {@code right=}, {@code leftshift=},
     * {@code rightshift=} and {@code middle=}. An unprefixed entry is run by the player.
     */
    private void dispatchLegacyCommand(GuiElement.Click click, String rawCommand) {
        HumanEntity who = click.getWhoClicked();
        String command = rawCommand.replace("%player%", who.getName()).trim();

        int guardSeparator = command.indexOf('=');
        if (guardSeparator >= 0) {
            String guard = command.substring(0, guardSeparator).trim().toLowerCase(Locale.ROOT);
            ClickType required = switch (guard) {
                case "left" -> ClickType.LEFT;
                case "right" -> ClickType.RIGHT;
                case "leftshift" -> ClickType.SHIFT_LEFT;
                case "rightshift" -> ClickType.SHIFT_RIGHT;
                case "middle" -> ClickType.MIDDLE;
                default -> null;
            };
            if (required != null) {
                if (click.getType() != required) return;
                command = command.substring(guardSeparator + 1).trim();
            }
        }

        int separator = command.indexOf('=');
        String prefix = separator < 0
                ? ""
                : command.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String argument = separator < 0 ? command : command.substring(separator + 1).trim();

        switch (prefix) {
            case "msg" -> who.sendMessage(menuConfig.parse(argument));
            case "console" -> dispatchConsoleCommandOnGlobalThread(argument);
            case "stopsound" -> {
                if (who instanceof Player player) player.stopSound(argument);
            }
            case "sound" -> playConfiguredSound(who, argument);
            default -> {
                if (who instanceof Player player && !command.isBlank()) {
                    String playerCommand = command.startsWith("/") ? command.substring(1) : command;
                    runPlayerCommandOnEntityThread(player, playerCommand);
                }
            }
        }
    }

    private void playConfiguredSound(HumanEntity who, String argument) {
        if (!(who instanceof Player player)) return;
        String[] parts = argument.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return;

        float volume = parseFloat(parts, 1, 1.0f, "volume");
        float pitch = parseFloat(parts, 2, 1.0f, "pitch");
        player.playSound(player.getLocation(), parts[0], volume, pitch);
    }

    private float parseFloat(String[] parts, int index, float fallback, String field) {
        if (parts.length <= index) return fallback;
        try {
            return Float.parseFloat(parts[index]);
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("[FreshLib] Invalid sound " + field + ": \"" + parts[index] + "\"");
            return fallback;
        }
    }

    private void dispatchExecutor(GuiElement.Click click, String entry) {
        int sep = entry.indexOf(':');
        String prefix;
        String argument;
        if (sep >= 0) {
            prefix = entry.substring(0, sep).trim().toLowerCase();
            argument = entry.substring(sep + 1).trim();
        } else {
            prefix = entry.toLowerCase();
            argument = "";
        }

        ActionExecutor executor = executorHandlers.get(prefix);
        if (executor != null) {
            executor.execute(click, argument);
        } else {
            plugin.getLogger().warning("[FreshLib] Unknown executor prefix: \"" + prefix + "\"");
        }
    }

    private void registerDefaultExecutors() {
        // close — closes the GUI
        registerExecutor("close", (click, arg) -> click.getGui().close());

        // command: <cmd> — runs a command as console
        registerExecutor("command", (click, arg) ->
                dispatchConsoleCommandOnGlobalThread(replacePlayerPlaceholder(click, arg)));

        // player-command: <cmd> — makes the clicking player run a command
        registerExecutor("player-command", (click, arg) -> {
            if (click.getWhoClicked() instanceof Player player) {
                runPlayerCommandOnEntityThread(player, replacePlayerPlaceholder(click, arg));
            }
        });

        // message: <text> — sends a chat message to the clicking player
        registerExecutor("message", (click, arg) -> {
            HumanEntity who = click.getWhoClicked();
            who.sendMessage(menuConfig.parse(arg));
        });
    }

    /** Expands the player placeholder consistently across legacy and executor action syntaxes. */
    private String replacePlayerPlaceholder(GuiElement.Click click, String value) {
        return value.replace("%player%", click.getWhoClicked().getName());
    }

    /** Console commands are owned by the global tick thread on Folia-derived servers. */
    private void dispatchConsoleCommandOnGlobalThread(String command) {
        runOnGlobalThread(() -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command));
    }

    /** Player commands must retain the player's region/entity context on Folia-derived servers. */
    private void runPlayerCommandOnEntityThread(Player player, String command) {
        player.getScheduler().run(plugin, task -> player.performCommand(command), null);
    }

    private void runOnGlobalThread(Runnable action) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin,
                task -> action.run());
    }

    private void registerDefaultMaterialResolvers() {
        registerMaterialResolver(new VanillaMaterialResolver());
        registerMaterialResolver(new PlayerHeadMaterialResolver());
        registerMaterialResolver(new NexoMaterialResolver());
        registerMaterialResolver(new ItemsAdderMaterialResolver());
        registerMaterialResolver(new HeadDatabaseMaterialResolver());
    }

    /**
     * Resolves a {@code material} string through registered {@link MaterialResolver}s (checked
     * last-registered-first, so resolvers registered after the defaults can override them),
     * falling back to a plain {@link Material#matchMaterial} lookup for ordinary material names.
     */
    private ItemStack resolveMaterial(String materialTag, Player player) {
        for (int i = materialResolvers.size() - 1; i >= 0; i--) {
            MaterialResolver resolver = materialResolvers.get(i);
            if (resolver.supports(materialTag)) {
                ItemStack resolved = resolver.resolve(materialTag, player);
                return resolved != null ? resolved : new ItemStack(Material.BARRIER);
            }
        }
        Material material = Material.matchMaterial(materialTag);
        return new ItemStack(material != null ? material : Material.STONE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ItemStack buildItemStack(ConfigurationSection section) {
        return buildItemStack(section, null);
    }

    private ItemStack buildItemStack(ConfigurationSection section, Player player) {
        return buildItemStack(section, player, "STONE", " ");
    }

    private ItemStack buildItemStack(ConfigurationSection section, Player player,
                                     String defaultMaterial, String defaultName) {
        String itemModel = section.getString("item-model");
        String customModelData = getStringOption(section, "custom-model-data", "custom-data");
        String tooltipStyle = section.getString("tooltip-style");
        Boolean hideTooltip = section.contains("hide-tooltip") ? section.getBoolean("hide-tooltip") : null;
        Integer damage = section.contains("damage") ? section.getInt("damage") : null;
        String material = section.getString("material", defaultMaterial);
        String texture = section.getString("texture");
        if (texture != null && !texture.isBlank()
                && (material.equalsIgnoreCase("PLAYER_HEAD") || material.equalsIgnoreCase("HEAD"))) {
            material = "head:" + texture;
        }

        String name = section.contains("name")
                ? section.getString("name", defaultName)
                : section.getString("display-name", defaultName);

        ItemStack item = buildItem(material, name, section.getStringList("lore"),
                itemModel, customModelData, tooltipStyle, hideTooltip, damage, player);

        int amount = section.getInt("amount", 1);
        if (amount > 0) {
            item.setAmount(amount);
        } else {
            plugin.getLogger().warning("[FreshLib] Item amount must be positive at " + section.getCurrentPath());
        }

        item.editMeta(meta -> {
            applyEnchantments(meta, section);

            EnumSet<ItemFlag> flags = parseItemFlags(section.getStringList("flags"));
            if (section.getBoolean("hide-enchantments", false)
                    || section.getBoolean("hide-enchants", false)) {
                flags.add(ItemFlag.HIDE_ENCHANTS);
            }
            if (!flags.isEmpty()) meta.addItemFlags(flags.toArray(ItemFlag[]::new));

            String color = section.getString("color");
            if (color != null && !color.isBlank()) applyColor(meta, color, section.getCurrentPath());
        });

        return item;
    }

    /**
     * Applies both supported menu enchantment formats:
     * <pre>
     * enchants:
     *   knockback: 2
     *
     * enchantments:
     *   - "KNOCKBACK 2"
     * </pre>
     */
    private void applyEnchantments(ItemMeta meta, ConfigurationSection itemSection) {
        applyEnchantmentSection(meta, itemSection.getConfigurationSection("enchants"));
        applyEnchantmentSection(meta, itemSection.getConfigurationSection("enchantments"));

        for (String configured : itemSection.getStringList("enchantments")) {
            String entry = configured.trim();
            if (entry.isEmpty()) continue;

            String[] parts = entry.split("\\s+");
            Enchantment enchantment = resolveEnchantment(parts[0]);
            if (enchantment == null) {
                plugin.getLogger().warning("[FreshLib] Invalid enchantment: \"" + parts[0] + "\"");
                continue;
            }

            int level = 1;
            if (parts.length >= 2) {
                try {
                    level = Integer.parseInt(parts[1]);
                } catch (NumberFormatException exception) {
                    plugin.getLogger().warning("[FreshLib] Invalid enchantment level in \""
                            + configured + "\" at " + itemSection.getCurrentPath());
                    continue;
                }
            }
            meta.addEnchant(enchantment, level, true);
        }
    }

    private void applyEnchantmentSection(ItemMeta meta, ConfigurationSection enchantSection) {
        if (enchantSection == null) return;

        for (String enchantKey : enchantSection.getKeys(false)) {
            Enchantment enchantment = resolveEnchantment(enchantKey);
            if (enchantment == null) {
                plugin.getLogger().warning("[FreshLib] Invalid enchantment: \"" + enchantKey + "\"");
                continue;
            }
            meta.addEnchant(enchantment, enchantSection.getInt(enchantKey, 1), true);
        }
    }

    private String getStringOption(ConfigurationSection section, String primary, String alias) {
        Object value = section.contains(primary) ? section.get(primary) : section.get(alias);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("deprecation")
    private Enchantment resolveEnchantment(String rawKey) {
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = normalized.contains(":")
                ? NamespacedKey.fromString(normalized)
                : NamespacedKey.minecraft(normalized);
        return key == null ? null : Enchantment.getByKey(key);
    }

    private EnumSet<ItemFlag> parseItemFlags(List<String> configuredFlags) {
        EnumSet<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        for (String rawFlag : configuredFlags) {
            if (rawFlag.equalsIgnoreCase("ALL")) return EnumSet.allOf(ItemFlag.class);
            try {
                flags.add(ItemFlag.valueOf(rawFlag.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("[FreshLib] Invalid item flag: \"" + rawFlag + "\"");
            }
        }
        return flags;
    }

    private void applyColor(org.bukkit.inventory.meta.ItemMeta meta, String rawColor, String path) {
        String[] parts = rawColor.split(",");
        if (parts.length != 3) {
            plugin.getLogger().warning("[FreshLib] Color must use R,G,B format at " + path + ": \"" + rawColor + "\"");
            return;
        }
        try {
            org.bukkit.Color color = org.bukkit.Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
            if (meta instanceof LeatherArmorMeta leatherArmorMeta) {
                leatherArmorMeta.setColor(color);
            } else if (meta instanceof PotionMeta potionMeta) {
                potionMeta.setColor(color);
            } else {
                plugin.getLogger().warning("[FreshLib] Color is only supported for leather armor and potions at " + path);
            }
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("[FreshLib] Invalid color at " + path + ": \"" + rawColor + "\"");
        }
    }

    /**
     * Builds an {@link ItemStack} from discrete display fields, resolving {@code material} through
     * {@link #resolveMaterial} and applying any of the optional item-data-component fields that are
     * non-null. This is the single shared implementation behind {@link #buildItemStack} (menu-YAML
     * driven) — consuming plugins building items from their own config POJOs (e.g. a Configurate
     * {@code @ConfigSerializable} settings class) should call this directly instead of hand-rolling
     * {@code Material.matchMaterial}/{@code editMeta} blocks themselves.
     *
     * @param material        Material tag/name, resolved via registered {@link MaterialResolver}s.
     * @param name             Display name (MiniMessage/legacy source string).
     * @param lore             Lore lines (MiniMessage/legacy source strings).
     * @param itemModel        Optional {@code item-model} namespaced key; null/blank to skip.
     * @param customModelData  Optional {@code custom-model-data} integer (as a string); null/blank to skip.
     * @param tooltipStyle     Optional {@code tooltip-style} namespaced key; null/blank to skip.
     * @param hideTooltip      Optional {@code hide-tooltip} flag; null to skip.
     * @param damage           Optional {@code damage} value; null to skip.
     * @param player           The player the item is being built for; may be null.
     */
    public ItemStack buildItem(String material, String name, List<String> lore,
                                String itemModel, String customModelData, String tooltipStyle,
                                Boolean hideTooltip, Integer damage, Player player) {
        ItemStack item = resolveMaterial(material, player);
        item.editMeta(meta -> {
            meta.displayName(menuConfig.parse(name));
            meta.lore(menuConfig.parseLore(lore));
            ItemDataComponents.applyItemModel(meta, itemModel);
            ItemDataComponents.applyCustomModelData(meta, customModelData);
            ItemDataComponents.applyTooltipStyle(meta, tooltipStyle);
            ItemDataComponents.applyHideTooltip(meta, hideTooltip);
            ItemDataComponents.applyDamage(meta, damage);
        });
        return item;
    }

    /**
     * Resolves the {@link GuiPageElement.PageAction} for a paginate element.
     * Supports both the legacy flat format ({@code action: "next"}) and the
     * nested executor format ({@code action.executor: ["nextpage"]}).
     */
    private GuiPageElement.PageAction resolvePaginateAction(ConfigurationSection section) {
        // Try nested action.executor first
        ConfigurationSection actionSection = section.getConfigurationSection("action");
        if (actionSection != null) {
            List<String> executors = actionSection.getStringList("executor");
            for (String ex : executors) {
                String lower = ex.toLowerCase();
                if (lower.contains("firstpage")) return GuiPageElement.PageAction.FIRST;
                if (lower.contains("prevpage") || lower.contains("previous")) return GuiPageElement.PageAction.PREVIOUS;
                if (lower.contains("lastpage")) return GuiPageElement.PageAction.LAST;
                if (lower.contains("nextpage") || lower.contains("next")) return GuiPageElement.PageAction.NEXT;
            }
        }

        // Fall back to legacy flat action string
        String action = section.getString("action", "next").toLowerCase();
        return switch (action) {
            case "first", "firstpage" -> GuiPageElement.PageAction.FIRST;
            case "prev", "prevpage", "previous" -> GuiPageElement.PageAction.PREVIOUS;
            case "last", "lastpage" -> GuiPageElement.PageAction.LAST;
            default -> GuiPageElement.PageAction.NEXT;
        };
    }

    private ItemStack buildInactiveItem(ConfigurationSection section) {
        if (section.isConfigurationSection("inactive")) {
            ConfigurationSection inactiveSection = section.getConfigurationSection("inactive");
            String matName = inactiveSection.getString("material", "AIR");
            Material mat = Material.matchMaterial(matName);
            if (mat == null || mat == Material.AIR) return null;
            return buildItemStack(inactiveSection, null, matName, " ");
        }

        // Legacy single-key formats
        String matName = section.getString("material-inactive", section.getString("inactive-material", ""));
        if (!matName.isEmpty()) {
            Material mat = Material.matchMaterial(matName);
            if (mat != null && mat != Material.AIR) {
                ItemStack item = new ItemStack(mat);
                item.editMeta(meta -> meta.displayName(menuConfig.parse(" ")));
                return item;
            }
        }
        return null;
    }
}

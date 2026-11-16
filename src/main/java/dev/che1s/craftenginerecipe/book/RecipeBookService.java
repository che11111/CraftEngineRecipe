package dev.che1s.craftenginerecipe.book;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Creates the CER recipe book and dispatches its player-configured gestures. */
public final class RecipeBookService {
    public static final Key ITEM_ID = Key.of("cer_recipe_book:recipe_book");
    public static final String DEFAULT_NAME = "<#D4AF37>CER·配方书</#D4AF37>";
    public static final List<String> DEFAULT_LORE = List.of(
            "<#B8C0CC>左键：{left}</#B8C0CC>",
            "<#B8C0CC>右键：{right}</#B8C0CC>",
            "<#B8C0CC>潜行左键：{sneak-left}</#B8C0CC>",
            "<#B8C0CC>潜行右键：{sneak-right}</#B8C0CC>",
            "<#8793A5>可在个性化设置中调整</#8793A5>");
    private static final String LEGACY_DEFAULT_NAME = "<#D4AF37>CER配方书</#D4AF37>";
    private static final List<String> LEGACY_DEFAULT_LORE = List.of(
            "<#B8C0CC>左键：{left}</#B8C0CC>",
            "<#B8C0CC>右键：{right}</#B8C0CC>",
            "<#B8C0CC>潜行左键：{sneak-left}</#B8C0CC>",
            "<#B8C0CC>潜行右键：{sneak-right}</#B8C0CC>",
            "<#8793A5>可在个性化设置中调整</#8793A5>");

    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)[&§][0-9a-fk-orx]");

    private final CraftEngineRecipePlugin plugin;

    public RecipeBookService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return this.plugin.getConfig().getBoolean("recipe-book.enabled", true);
    }

    public boolean isRecipeBook(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        try {
            return ITEM_ID.equals(CraftEngineItems.getCustomItemId(stack));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public ItemStack create(Player player) {
        if (!this.enabled()) return null;
        try {
            if (CraftEngineItems.byId(ITEM_ID) == null) return null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        ItemStack stack = ItemBridge.build(ITEM_ID, player, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        meta.displayName(this.parse(this.nameTemplate(player)));
        meta.lore(this.lore(player));
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean give(Player player) {
        ItemStack book = this.create(player);
        if (book == null) return false;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(book);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return true;
    }

    public List<Component> lore(Player player) {
        PlayerPreferences preferences = this.plugin.playerPreferences();
        PlayerPreferences.BookBindings bindings = preferences == null
                ? PlayerPreferences.BookBindings.defaults()
                : preferences.bookBindings(player.getUniqueId());
        List<Component> lines = new ArrayList<>();
        for (String configured : this.loreTemplates(player)) {
            String line = configured == null ? "" : configured;
            line = line.replace("{left}", actionLabel(player, bindings.left()))
                    .replace("{right}", actionLabel(player, bindings.right()))
                    .replace("{sneak-left}", actionLabel(player, bindings.sneakLeft()))
                    .replace("{sneak-right}", actionLabel(player, bindings.sneakRight()));
            lines.add(this.parse(line));
        }
        return List.copyOf(lines);
    }

    public void perform(Player player, PlayerPreferences.BookSlot slot) {
        if (!this.enabled()) return;
        if (!this.plugin.data().isReady()) {
            player.sendMessage(this.plugin.lang(player).message("messages.data-not-ready",
                    "reason", this.plugin.lang(player).text("messages.not-ready-reason")));
            return;
        }
        PlayerPreferences preferences = this.plugin.playerPreferences();
        PlayerPreferences.BookAction action = preferences == null
                ? PlayerPreferences.BookAction.defaultFor(slot)
                : preferences.bookBindings(player.getUniqueId()).action(slot);
        action = this.resolveAutomaticAction(player, action);
        switch (action) {
            case AUTO_OFFHAND_LEFT, AUTO_OFFHAND_RIGHT -> {
                // 自动动作必须在 perform 前按副手状态解析。
            }
            case ITEM_PAGE -> this.plugin.browser(player).openNamespaceBrowser(player);
            case TABLE_PAGE -> this.plugin.browser(player).openWorkstationBrowser(player);
            case SEARCH_PAGE -> this.plugin.dialogs().search().open(player);
            case OFFHAND_RECIPE -> this.plugin.browser(player).openOffhandRecipe(player);
            case OFFHAND_USAGE -> this.plugin.browser(player).openOffhandUsage(player);
            case TARGET_RECIPE -> this.plugin.browser(player).openTargetBlockRecipe(player);
            case TARGET_USAGE -> this.plugin.browser(player).openTargetBlockUsage(player);
            case PERSONALIZATION -> this.plugin.dialogs().personalization().open(player);
        }
    }

    public static String actionKey(PlayerPreferences.BookAction action) {
        return switch (action) {
            case AUTO_OFFHAND_LEFT -> "ui.personalization.book-action-auto-left";
            case AUTO_OFFHAND_RIGHT -> "ui.personalization.book-action-auto-right";
            case ITEM_PAGE -> "ui.personalization.book-action-item-page";
            case TABLE_PAGE -> "ui.personalization.book-action-table-page";
            case SEARCH_PAGE -> "ui.personalization.book-action-search";
            case OFFHAND_RECIPE -> "ui.personalization.book-action-offhand";
            case OFFHAND_USAGE -> "ui.personalization.book-action-offhand-usage";
            case TARGET_RECIPE -> "ui.personalization.book-action-target";
            case TARGET_USAGE -> "ui.personalization.book-action-target-usage";
            case PERSONALIZATION -> "ui.personalization.book-action-personalization";
        };
    }

    private PlayerPreferences.BookAction resolveAutomaticAction(
            Player player, PlayerPreferences.BookAction action) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return resolveAutomaticAction(action,
                offhand.getType().isAir() || offhand.getAmount() <= 0);
    }

    static PlayerPreferences.BookAction resolveAutomaticAction(
            PlayerPreferences.BookAction action, boolean offhandEmpty) {
        if (action != PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT
                && action != PlayerPreferences.BookAction.AUTO_OFFHAND_RIGHT) {
            return action;
        }
        if (action == PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT) {
            return offhandEmpty ? PlayerPreferences.BookAction.ITEM_PAGE
                    : PlayerPreferences.BookAction.OFFHAND_RECIPE;
        }
        return offhandEmpty ? PlayerPreferences.BookAction.TABLE_PAGE
                : PlayerPreferences.BookAction.OFFHAND_USAGE;
    }

    private String actionLabel(Player player, PlayerPreferences.BookAction action) {
        return PlainTextComponentSerializer.plainText().serialize(
                this.plugin.lang(player).component(actionKey(action)));
    }

    public String nameTemplate(Player player) {
        LanguageManager.Localized language = this.plugin.lang(player);
        String localizedOverride = this.plugin.getConfig().getString(
                "recipe-book.name-overrides." + language.locale());
        if (localizedOverride != null && !localizedOverride.isBlank()) {
            return localizedOverride;
        }
        String legacyOverride = this.plugin.getConfig().getString("recipe-book.name");
        if (legacyOverride != null && !legacyOverride.isBlank()
                && !legacyOverride.equals(LEGACY_DEFAULT_NAME)) {
            return legacyOverride;
        }
        return language.text("ui.recipe-book.name");
    }

    public List<String> loreTemplates(Player player) {
        LanguageManager.Localized language = this.plugin.lang(player);
        List<String> localized = this.plugin.getConfig().getStringList(
                "recipe-book.lore-overrides." + language.locale());
        if (!localized.isEmpty()) return List.copyOf(localized);
        List<String> legacy = this.plugin.getConfig().getStringList("recipe-book.lore");
        if (!legacy.isEmpty() && !legacy.equals(LEGACY_DEFAULT_LORE)) {
            return List.copyOf(legacy);
        }
        return language.list("ui.recipe-book.lore");
    }

    private Component parse(String raw) {
        String value = raw == null ? "" : raw.replace('§', '&');
        try {
            if (LEGACY_COLOR.matcher(value).find()) {
                return CerConfig.noItalic(LegacyComponentSerializer.legacyAmpersand().deserialize(value));
            }
            return this.plugin.config() == null
                    ? CerConfig.noItalic(CraftEngineRecipePlugin.mini().deserialize(value))
                    : this.plugin.config().mm(value);
        } catch (RuntimeException ignored) {
            return Component.text(value);
        }
    }

}

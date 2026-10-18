package dev.leon.cer.config;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.i18n.LanguageManager;
import dev.leon.cer.util.ResourcePackTranslations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.core.util.AdventureHelper;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CerConfig {

    public static final int ROWS = 6;
    public static final String DISABLED_ITEM_MODEL = "cer:blank_items";
    private static final Pattern FULL_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern ID_PATH = Pattern.compile("[a-z0-9/._-]+");
    private static final List<String> BUTTON_PATHS = List.of("category-bar.prev-category", "category-bar.next-category",
            "content.prev-page", "content.next-page", "content.back", "content.search", "content.empty");

    private static final List<String> DEFAULT_LAYOUT_ROWS = List.of(
            "<CCCCCCC>",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "B#L#S#R##"
    );

    private final CraftEngineRecipePlugin plugin;

    private String browserTitle;
    private List<String> layoutRows;
    private char categoryIconChar;
    private char contentAreaChar;
    private boolean categoryFollowCe;
    private List<String> categoryList;
    private String categoryIconName;
    private List<String> categoryIconLore;
    private List<String> subIconLore;
    private ButtonDef prevCategory;
    private ButtonDef nextCategory;
    private ButtonDef prevPage;
    private ButtonDef nextPage;
    private ButtonDef back;
    private ButtonDef search;
    private List<String> itemLore;
    private ButtonDef empty;
    private int recipeRotateInterval;
    private String recipeTitle;
    private Map<String, String> recipeTitles;
    private String searchDialogTitle;
    private String searchDialogLabel;
    private String searchDialogHint;
    private String searchResultTitle;
    private int searchMaxLength;
    private int searchMaxResults;
    private List<String> searchLanguages;
    private SoundSettings sounds;
    private boolean hotkeyEnabled;
    private boolean hotkeyRequireSneak;
    private String adminOnlyHint;
    private String noPermissionHint;

    public CerConfig(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.plugin.saveDefaultConfig();
        this.plugin.reloadConfig();
        FileConfiguration c = this.plugin.getConfig();
        LanguageManager lang = this.plugin.lang();
        lang.load(c.contains("language", true) ? c.getString("language") : c.getString("locale", "zh_CN"));

        this.browserTitle = lang.uiText(c, "browser.title");
        this.loadLayout(c);

        String source = c.getString("category-bar.source", "ce");
        this.categoryFollowCe = !"custom".equalsIgnoreCase(source == null ? "" : source.trim());
        this.categoryList = List.copyOf(c.getStringList("category-bar.list"));
        this.categoryIconName = lang.uiText(c, "category-bar.icon-name");
        this.categoryIconLore = lang.uiList(c, "category-bar.icon-lore");
        this.subIconLore = lang.uiList(c, "category-bar.sub-icon-lore");
        this.prevCategory = ButtonDef.of(c, lang, "category-bar.prev-category", Material.ARROW);
        this.nextCategory = ButtonDef.of(c, lang, "category-bar.next-category", Material.ARROW);
        this.adminOnlyHint = lang.uiText(c, "category-bar.admin-only-hint");
        this.noPermissionHint = lang.uiText(c, "category-bar.no-permission-hint");

        this.prevPage = ButtonDef.of(c, lang, "content.prev-page", Material.SPECTRAL_ARROW);
        this.nextPage = ButtonDef.of(c, lang, "content.next-page", Material.SPECTRAL_ARROW);
        this.back = ButtonDef.of(c, lang, "content.back", Material.BARRIER);
        this.search = ButtonDef.of(c, lang, "content.search", Material.COMPASS);
        this.itemLore = lang.uiList(c, "content.item-lore");
        this.empty = ButtonDef.of(c, lang, "content.empty", Material.BARRIER);

        this.recipeRotateInterval = clamp(c.getInt("recipe.rotate-interval", 30), 5, 200);
        this.recipeTitle = lang.uiText(c, "recipe.title");
        this.recipeTitles = new HashMap<>();
        this.recipeTitles.put("CRAFTING", lang.uiText(c, "recipe.title-crafting"));
        this.recipeTitles.put("SMELTING", lang.uiText(c, "recipe.title-smelting"));
        this.recipeTitles.put("BLASTING", lang.uiText(c, "recipe.title-blasting"));
        this.recipeTitles.put("SMOKING", lang.uiText(c, "recipe.title-smoking"));
        this.recipeTitles.put("CAMPFIRE_COOKING", lang.uiText(c, "recipe.title-campfire"));
        this.recipeTitles.put("STONECUTTING", lang.uiText(c, "recipe.title-stonecutting"));
        this.recipeTitles.put("SMITHING", lang.uiText(c, "recipe.title-smithing"));
        this.recipeTitles.put("BREWING", lang.uiText(c, "recipe.title-brewing"));
        this.recipeTitles.put("NONE", lang.uiText(c, "recipe.title-none"));

        this.searchDialogTitle = lang.uiText(c, "search.dialog-title");
        this.searchDialogLabel = lang.uiText(c, "search.dialog-label");
        this.searchDialogHint = lang.uiText(c, "search.dialog-hint");
        this.searchResultTitle = lang.uiText(c, "search.result-title");
        this.searchMaxLength = clamp(c.getInt("search.max-length", 32), 1, 64);
        this.searchMaxResults = clamp(c.getInt("search.max-results", 200), 1, 1000);
        this.searchLanguages = ResourcePackTranslations.normalizeLanguages(c.getStringList("search.languages"));

        this.sounds = SoundSettings.load(c);

        this.hotkeyEnabled = c.getBoolean("hotkey.enabled", true);
        this.hotkeyRequireSneak = c.getBoolean("hotkey.require-sneak", true);
        this.diagnose(c);
    }

    private void diagnose(FileConfiguration config) {
        for (int i = 0; i < this.categoryList.size(); i++) {
            String id = this.categoryList.get(i);
            if (!validId(id)) this.warn("logs.config-invalid-id", "category-bar.list[" + i + "]", id);
        }
        for (String button : BUTTON_PATHS) {
            String model = config.getString(button + ".item-model", "");
            if (!model.isBlank() && !validId(model) && !ID_PATH.matcher(model).matches()) {
                this.warn("logs.config-invalid-id", button + ".item-model", model);
            }
            String material = config.getString(button + ".material");
            if (material != null) {
                String materialName = material.contains(":") ? material.substring(material.indexOf(':') + 1) : material;
                if (Material.matchMaterial(materialName) == null) {
                    this.warn("logs.config-invalid-material", button + ".material", material);
                }
            }
        }
    }

    /** Called once a new CraftEngine category snapshot is available; diagnostics never alter config. */
    public void validateReferences(Set<Key> categories) {
        if (this.categoryFollowCe) return;
        for (int i = 0; i < this.categoryList.size(); i++) {
            String id = this.categoryList.get(i);
            if (validId(id) && !categories.contains(Key.of(id))) {
                this.warn("logs.config-missing-category", "category-bar.list[" + i + "]", id);
            }
        }
    }

    private static boolean validId(String value) {
        return value != null && FULL_ID.matcher(value).matches();
    }

    private void warn(String message, String path, String value) {
        this.plugin.getLogger().warning(this.plugin.lang().text(message, "path", "config.yml:" + path, "value", value));
    }

    private void loadLayout(FileConfiguration c) {
        List<String> rows = c.getStringList("browser.layout");
        this.layoutRows = rows.isEmpty() ? DEFAULT_LAYOUT_ROWS : List.copyOf(rows);
        this.categoryIconChar = firstChar(c.getString("category-bar.icon-char"), 'C');
        this.contentAreaChar = firstChar(c.getString("content.area-char"), 'X');
    }

    private static char firstChar(String s, char fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        return s.trim().charAt(0);
    }

    public Component mm(String miniMessage) {
        String raw = miniMessage == null ? "" : miniMessage;
        if (raw.contains("<image:") || raw.contains("<shift:") || raw.contains("<l10n:")) {
            // CraftEngine relocates Adventure. JSON bridges its custom tags to Paper's components.
            return noItalic(GsonComponentSerializer.gson().deserialize(
                    AdventureHelper.componentToJson(AdventureHelper.miniMessage().deserialize(raw))));
        }
        return noItalic(CraftEngineRecipePlugin.mini().deserialize(raw));
    }

    public static Component noItalic(Component component) {
        if (component == null) {
            return null;
        }
        if (component.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            return component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        return component;
    }

    public Component mm(String miniMessage, String... kv) {
        String s = miniMessage == null ? "" : miniMessage;
        int i = 0;
        while (i + 1 < kv.length) {
            s = s.replace("<" + kv[i] + ">", kv[i + 1] == null ? "" : kv[i + 1]);
            i += 2;
        }
        return this.mm(s);
    }

    public Component mmEscaped(String miniMessage, String... kv) {
        String s = miniMessage == null ? "" : miniMessage;
        int i = 0;
        while (i + 1 < kv.length) {
            s = s.replace("<" + kv[i] + ">", MiniMessageEscape.escape(kv[i + 1]));
            i += 2;
        }
        return this.mm(s);
    }

    private static Material parseMaterial(String name) {
        if (name == null || name.equalsIgnoreCase("air")) {
            return Material.AIR;
        }
        String n = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        Material m = Material.matchMaterial(n);
        return m == null ? Material.AIR : m;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public String browserTitle() {
        return this.browserTitle;
    }

    public List<String> layoutRows() {
        return this.layoutRows;
    }

    public char categoryIconChar() {
        return this.categoryIconChar;
    }

    public char contentAreaChar() {
        return this.contentAreaChar;
    }

    public boolean categoryFollowCe() {
        return this.categoryFollowCe;
    }

    public List<String> categoryList() {
        return this.categoryList;
    }

    public String categoryIconName() {
        return this.categoryIconName;
    }

    public List<String> categoryIconLore() {
        return this.categoryIconLore;
    }

    public List<String> subIconLore() {
        return this.subIconLore;
    }

    public ButtonDef prevCategory() {
        return this.prevCategory;
    }

    public ButtonDef nextCategory() {
        return this.nextCategory;
    }

    public String adminOnlyHint() {
        return this.adminOnlyHint;
    }

    public String noPermissionHint() {
        return this.noPermissionHint;
    }

    public ButtonDef prevPage() {
        return this.prevPage;
    }

    public ButtonDef nextPage() {
        return this.nextPage;
    }

    public ButtonDef back() {
        return this.back;
    }

    public ButtonDef search() {
        return this.search;
    }

    public List<String> itemLore() {
        return this.itemLore;
    }

    public ButtonDef empty() {
        return this.empty;
    }

    public int recipeRotateInterval() {
        return this.recipeRotateInterval;
    }

    public String recipeTitleFor(String type) {
        return this.recipeTitles.getOrDefault(type, this.recipeTitle);
    }

    public String searchDialogTitle() {
        return this.searchDialogTitle;
    }

    public String searchDialogLabel() {
        return this.searchDialogLabel;
    }

    public String searchDialogHint() {
        return this.searchDialogHint;
    }

    public String searchResultTitle() {
        return this.searchResultTitle;
    }

    public int searchMaxLength() {
        return this.searchMaxLength;
    }

    public int searchMaxResults() {
        return this.searchMaxResults;
    }

    public List<String> searchLanguages() {
        return this.searchLanguages;
    }

    public SoundSettings sounds() {
        return this.sounds;
    }

    public boolean hotkeyEnabled() {
        return this.hotkeyEnabled;
    }

    public boolean hotkeyRequireSneak() {
        return this.hotkeyRequireSneak;
    }

    public record ButtonDef(Material material, String itemModel, char symbol, String name, List<String> lore) {

        static ButtonDef of(FileConfiguration c, LanguageManager lang, String path, Material defMaterial) {
            Material m = parseMaterial(c.getString(path + ".material", defMaterial.getKey().toString()));
            String itemModel = c.getString(path + ".item-model", "");
            char symbol = firstChar(c.getString(path + ".char"), '\u0000');
            String name = lang.uiText(c, path + ".name");
            List<String> lore = lang.uiList(c, path + ".lore");
            return new ButtonDef(m, itemModel, symbol, name, lore);
        }
    }

    private static final class MiniMessageEscape {

        private MiniMessageEscape() {
        }

        static String escape(String in) {
            if (in == null) {
                return "";
            }
            return in.replace("<", "\\<");
        }
    }
}

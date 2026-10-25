package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.util.ResourcePackTranslations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.core.util.AdventureHelper;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class CerConfig {

    public static final int ROWS = 6;
    private static final Pattern FULL_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern ID_PATH = Pattern.compile("[a-z0-9/._-]+");
    private static final List<String> BUTTON_PATHS = List.of("category-bar.prev-category", "category-bar.next-category",
            "content.prev-page", "content.next-page", "content.back", "content.search", "content.hand",
            "content.query", "content.close", "content.info", "content.workstations", "content.glasses",
            "content.empty");

    private static final List<String> DEFAULT_LAYOUT_ROWS = List.of(
            "<CCCCCCC>",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "BHLISGRT#"
    );

    private final CraftEngineRecipePlugin plugin;
    private final String requestedMenuPack;
    private String menuPackId;
    private String menuDisplayName;

    private String browserTitle;
    private String workstationTitle;
    private int browserTitleOffset;
    private int browserTitleShift;
    private List<String> layoutRows;
    private char categoryIconChar;
    private char contentAreaChar;
    private Material reservedMaterial;
    private String reservedItemModel;
    private String disabledItemModel;
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
    private ButtonDef hand;
    private ButtonDef query;
    private ButtonDef close;
    private ButtonDef info;
    private ButtonDef workstations;
    private ButtonDef glasses;
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
    private boolean rememberLastPage;
    private long rememberLastPageMinutes;
    private boolean workstationsToggle;
    private boolean disableBackButton;
    private String adminOnlyHint;
    private String noPermissionHint;

    public CerConfig(CraftEngineRecipePlugin plugin) {
        this(plugin, null);
    }

    public CerConfig(CraftEngineRecipePlugin plugin, String menuPack) {
        this.plugin = plugin;
        this.requestedMenuPack = menuPack;
    }

    public void load() {
        this.plugin.saveDefaultConfig();
        try {
            YamlDefaultsUpdater.update(this.plugin.getDataFolder().toPath().resolve("config.yml"),
                    this.plugin.getResource("config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.config-update-failed", "error", e.getMessage()));
        }
        this.plugin.reloadConfig();
        FileConfiguration c = this.plugin.getConfig();
        this.menuPackId = this.requestedMenuPack == null || this.requestedMenuPack.isBlank()
                ? this.plugin.menuPacks().defaultPack() : this.requestedMenuPack;
        this.menuDisplayName = this.menuPackId;
        FileConfiguration ui = this.loadMenuConfig(this.menuPackId);
        this.menuDisplayName = ui.getString("display-name", this.menuPackId);
        LanguageManager lang = this.plugin.lang();
        lang.load(c.getString("language", "zh_CN"));

        this.browserTitleOffset = ui.getInt("browser.title-offset", -8);
        this.browserTitleShift = ui.getInt("browser.title-shift", -168);
        this.browserTitle = this.menuTitle(ui.getString("browser.title"), lang.text("ui.browser.title"));
        this.workstationTitle = this.menuTitle(ui.getString("browser.workstations-title"),
                lang.text("ui.workstations.title"));
        this.loadLayout(ui);
        this.reservedMaterial = parseMaterial(ui.getString("browser.reserved.material", "air"));
        this.reservedItemModel = ui.getString("browser.reserved.item-model", "");
        this.disabledItemModel = ui.getString("browser.disabled-item-model", "");

        String source = ui.getString("category-bar.source", "ce");
        this.categoryFollowCe = !"custom".equalsIgnoreCase(source == null ? "" : source.trim());
        this.categoryList = List.copyOf(ui.getStringList("category-bar.list"));
        this.categoryIconName = lang.uiText(ui, "category-bar.icon-name");
        this.categoryIconLore = lang.uiList(ui, "category-bar.icon-lore");
        this.subIconLore = lang.uiList(ui, "category-bar.sub-icon-lore");
        this.prevCategory = ButtonDef.of(ui, lang, "category-bar.prev-category", Material.ARROW);
        this.nextCategory = ButtonDef.of(ui, lang, "category-bar.next-category", Material.ARROW);
        this.adminOnlyHint = lang.uiText(ui, "category-bar.admin-only-hint");
        this.noPermissionHint = lang.uiText(ui, "category-bar.no-permission-hint");

        this.prevPage = ButtonDef.of(ui, lang, "content.prev-page", Material.SPECTRAL_ARROW);
        this.nextPage = ButtonDef.of(ui, lang, "content.next-page", Material.SPECTRAL_ARROW);
        this.back = ButtonDef.of(ui, lang, "content.back", Material.BARRIER);
        this.search = ButtonDef.of(ui, lang, "content.search", Material.COMPASS);
        this.hand = ButtonDef.of(ui, lang, "content.hand", Material.PAPER, "cer:palm_blue_cuff", 'H');
        this.query = ButtonDef.of(ui, lang, "content.query", Material.PAPER, "cer:palm_blue_cuff", '\u0000');
        this.close = ButtonDef.of(ui, lang, "content.close", Material.BARRIER);
        this.info = ButtonDef.of(ui, lang, "content.info", Material.PAPER, "cer:info_icon", 'I');
        this.workstations = ButtonDef.of(ui, lang, "content.workstations", Material.CRAFTING_TABLE,
                "cer:crafting_table", 'T');
        this.glasses = ButtonDef.of(ui, lang, "content.glasses", Material.PAPER, "cer:glasses", 'G');
        this.itemLore = lang.uiList(ui, "content.item-lore");
        this.empty = ButtonDef.of(ui, lang, "content.empty", Material.BARRIER);

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
        this.searchResultTitle = this.menuTitle(ui.getString("browser.search-title"),
                lang.text("ui.search.result-title"));
        this.searchMaxLength = clamp(c.getInt("search.max-length", 32), 1, 64);
        this.searchMaxResults = clamp(c.getInt("search.max-results", 200), 1, 1000);
        this.searchLanguages = ResourcePackTranslations.normalizeLanguages(c.getStringList("search.languages"));

        this.sounds = SoundSettings.load(ui);

        this.hotkeyEnabled = c.getBoolean("hotkey.enabled", true);
        this.hotkeyRequireSneak = c.getBoolean("hotkey.require-sneak", true);
        this.rememberLastPage = c.getBoolean("browser.remember-last-page", true);
        this.rememberLastPageMinutes = Math.max(1,
                c.getLong("browser.remember-last-page-minutes", 3));
        this.workstationsToggle = ui.getBoolean("behavior.workstations-toggle", false);
        this.disableBackButton = ui.getBoolean("behavior.disable-back-button", false);
        this.diagnose(ui);
    }

    private FileConfiguration loadMenuConfig(String packId) {
        Path file = this.plugin.getDataFolder().toPath().resolve("resources").resolve(packId).resolve("menu.yml");
        YamlConfiguration menu = new YamlConfiguration();
        try {
            try (InputStream bundled = this.plugin.getResource("resources/" + packId + "/menu.yml")) {
                if (bundled != null) YamlDefaultsUpdater.update(file, bundled);
            }
            if (Files.isRegularFile(file)) {
                menu.load(file.toFile());
            } else {
                try (InputStream input = this.plugin.getResource("resources/" + packId + "/menu.yml")) {
                    if (input == null) throw new IOException("Missing menu.yml");
                    menu.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.plugin.getLogger().warning("Cannot load menu pack " + packId + ": " + error.getMessage());
        }
        return menu;
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

    /** 在取得新的 CraftEngine 分类快照后调用；诊断过程绝不会修改配置。 */
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
        if (s == null) return fallback;
        String value = s.trim();
        return value.isBlank() ? fallback : value.charAt(0);
    }

    private String menuTitle(String template, String content) {
        return resolveMenuTitle(template, content, this.browserTitleOffset, this.browserTitleShift);
    }

    static String resolveMenuTitle(String template, String content, int offset, int shift) {
        if (template == null || template.isBlank()) return content;
        String rendered = template
                .replace("<offset>", String.valueOf(offset))
                .replace("<shift>", String.valueOf(shift));
        // 图片前缀和位移由菜单包负责，本地化标题仍保留在 lang/ 中。
        return rendered.contains("<title>") ? rendered.replace("<title>", content) : rendered;
    }

    public Component mm(String miniMessage) {
        String raw = miniMessage == null ? "" : miniMessage;
        if (raw.contains("<image:") || raw.contains("<shift:") || raw.contains("<l10n:")) {
            // CraftEngine 会重定位 Adventure。这里通过 JSON 将其自定义标签转换为 Paper 组件。
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

    public String workstationTitle() {
        return this.workstationTitle;
    }

    public String menuPackId() {
        return this.menuPackId;
    }

    public String menuDisplayName() {
        return this.menuDisplayName;
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

    public Material reservedMaterial() {
        return this.reservedMaterial;
    }

    public String reservedItemModel() {
        return this.reservedItemModel;
    }

    public String disabledItemModel() {
        return this.disabledItemModel;
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

    public ButtonDef hand() {
        return this.hand;
    }

    public ButtonDef query() {
        return this.query;
    }

    public ButtonDef close() {
        return this.close;
    }

    public ButtonDef info() {
        return this.info;
    }

    public ButtonDef workstations() {
        return this.workstations;
    }

    public ButtonDef glasses() {
        return this.glasses;
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

    public boolean rememberLastPage() {
        return this.rememberLastPage;
    }

    public long rememberLastPageMinutes() {
        return this.rememberLastPageMinutes;
    }

    public boolean workstationsToggle() {
        return this.workstationsToggle;
    }

    public boolean disableBackButton() {
        return this.disableBackButton;
    }

    public record ButtonDef(Material material, String itemModel, char symbol, String name, List<String> lore) {

        static ButtonDef of(FileConfiguration c, LanguageManager lang, String path, Material defMaterial) {
            return of(c, lang, path, defMaterial, "", firstChar(c.getString(path + ".char"), '\u0000'));
        }

        static ButtonDef of(FileConfiguration c, LanguageManager lang, String path, Material defMaterial,
                            String defItemModel, char defSymbol) {
            Material m = parseMaterial(c.getString(path + ".material", defMaterial.getKey().toString()));
            String itemModel = c.getString(path + ".item-model", defItemModel);
            char symbol = firstChar(c.getString(path + ".char", String.valueOf(defSymbol)), '\u0000');
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

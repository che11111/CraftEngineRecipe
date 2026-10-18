package dev.leon.cer.data;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.i18n.LanguageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 管理分类命名空间的可见性、排序数据文件（categories.yml），以及菜单中的自定义按钮。
 *
 * <p>文件的 {@code entries} 是一个有序列表，每项既可以是命名空间（{@code id} + {@code visibility} + {@code permission}），
 * 也可以是自定义按钮（{@code display} + {@code actions}），它们可以自由混排，列表顺序即菜单显示顺序。</p>
 *
 * <p>自定义按钮支持以下 action（以字符串列表形式写在 actions 下，键为点击类型：all/left/right/shift_left/shift_right/middle）：</p>
 * <ul>
 *   <li>{@code sound: <声音>-<音量>-<音调>} —— 播放声音（音量、音调可省略）</li>
 *   <li>{@code close} —— 关闭菜单</li>
 *   <li>{@code delay: <秒>}（或 {@code delay: <tick>t}）—— 延时后执行后续动作</li>
 *   <li>{@code console: <指令>} —— 以控制台身份执行</li>
 *   <li>{@code command: / player: <指令>} —— 以玩家身份执行</li>
 *   <li>{@code op: <指令>} —— 以临时 OP 身份执行</li>
 * </ul>
 */
public final class CategoryNamespaces {

    public enum Visibility {
        ADMIN,
        PLAYER,
        PERMISSION
    }

    public sealed interface Entry {
        String id();
    }

    public record NamespaceEntry(
            String id,
            Visibility visibility,
            String permission,
            String iconName,
            List<String> itemLore,
            List<String> iconLore,
            List<String> subIconLore,
            Map<String, Set<String>> hiddenItems,
            Set<String> hiddenCategories) implements Entry {
    }

    public record ButtonEntry(String id, ButtonDisplay display, Map<String, List<String>> actions, Visibility visibility, String permission) implements Entry {

        public List<String> actionsFor(ClickType clickType) {
            if (clickType == null) {
                return this.actions.getOrDefault("all", List.of());
            }
            String key = clickType.name().toLowerCase(Locale.ROOT);
            List<String> list = this.actions.get(key);
            if (list == null) {
                list = this.actions.get("all");
            }
            return list == null ? List.of() : list;
        }
    }

    public record ButtonDisplay(String material, String name, List<String> lore) {
    }

    private final LanguageManager language;
    private final Logger logger;
    private final File file;
    private final File extraFile;
    private final Map<String, NamespaceEntry> namespaceIndex = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<NamespaceEntry> extra = new ArrayList<>();
    private final Map<String, Set<String>> legacyHiddenItems = new LinkedHashMap<>();
    private final Map<String, Set<String>> hiddenItems = new LinkedHashMap<>();
    private final Set<String> hiddenCategories = new LinkedHashSet<>();

    public CategoryNamespaces(CraftEngineRecipePlugin plugin) {
        this(plugin.getDataFolder(), plugin.lang(), plugin.getLogger());
    }

    CategoryNamespaces(File dataFolder, LanguageManager language, Logger logger) {
        this.language = language;
        this.logger = logger;
        this.file = new File(dataFolder, "categories.yml");
        this.extraFile = new File(dataFolder, "categories-unapplied.yml");
    }

    public void load() {
        this.namespaceIndex.clear();
        this.entries.clear();
        this.extra.clear();
        this.legacyHiddenItems.clear();
        this.hiddenItems.clear();
        this.hiddenCategories.clear();
        if (this.file.exists()) {
            this.loadCategoriesFile();
        }
        if (this.extraFile.exists()) {
            this.loadExtraFile();
        }
        this.rebuildFilters();
    }

    private void loadCategoriesFile() {
        YamlConfiguration yaml = readYaml(this.file);
        List<?> list = yaml.getList("entries");
        if (list == null) {
            list = yaml.getList("namespaces");
        }
        if (list != null) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (m.get("display") instanceof Map<?, ?>) {
                    ButtonEntry button = parseButton(m);
                    if (button != null) {
                        this.entries.add(button);
                    }
                } else {
                    NamespaceEntry ns = parseNamespace(m);
                    if (ns != null) {
                        this.entries.add(ns);
                    }
                }
            }
        }
        this.legacyHiddenItems.putAll(parseHiddenItems(yaml.get("hidden-items")));
    }

    private static Map<String, Set<String>> parseHiddenItems(Object value) {
        Map<String, Set<String>> rules = new LinkedHashMap<>();
        if (value instanceof List<?> hiddenList) {
            for (Object o : hiddenList) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String categoryId = str(m.get("category"));
                if (categoryId.isBlank()) continue;
                Set<String> set = new LinkedHashSet<>();
                if (m.get("items") instanceof List<?> itemsList) {
                    for (Object io : itemsList) {
                        String s = str(io);
                        if (!s.isBlank()) set.add(s);
                    }
                }
                if (!set.isEmpty()) {
                    rules.computeIfAbsent(categoryId, ignored -> new LinkedHashSet<>()).addAll(set);
                }
            }
        }
        rules.replaceAll((category, items) -> Collections.unmodifiableSet(items));
        return Collections.unmodifiableMap(rules);
    }

    private static Set<String> parseHiddenCategories(Object value) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : parseStringList(value)) {
            if (!id.isBlank()) ids.add(id);
        }
        return Collections.unmodifiableSet(ids);
    }

    private void rebuildFilters() {
        this.hiddenItems.clear();
        this.hiddenCategories.clear();
        mergeHiddenItems(this.hiddenItems, this.legacyHiddenItems);
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : this.allEntries()) {
            if (entry instanceof NamespaceEntry namespace && seen.add(namespace.id())) {
                mergeHiddenItems(this.hiddenItems, namespace.hiddenItems());
                this.hiddenCategories.addAll(namespace.hiddenCategories());
            }
        }
        this.hiddenItems.replaceAll((category, items) -> Set.copyOf(items));
    }

    private static void mergeHiddenItems(Map<String, Set<String>> target, Map<String, Set<String>> source) {
        source.forEach((category, items) ->
                target.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).addAll(items));
    }

    private void loadExtraFile() {
        YamlConfiguration yaml = readYaml(this.extraFile);
        List<?> list = yaml.getList("entries");
        if (list == null) {
            return;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            NamespaceEntry ns = parseNamespace(m);
            if (ns != null) {
                this.extra.add(ns);
            }
        }
    }

    private YamlConfiguration readYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException(this.language.text("logs.category-read-failed", "file", file.getName()), e);
        }
        return yaml;
    }

    private static NamespaceEntry parseNamespace(Map<?, ?> m) {
        String id = str(m.get("id"));
        if (id.isBlank()) {
            return null;
        }
        Visibility visibility = parseVisibility(str(m.get("visibility")));
        String permission = str(m.get("permission"));
        String iconName = str(m.get("icon-name"));
        List<String> itemLore = parseStringList(m.get("item-lore"));
        List<String> iconLore = parseStringList(m.get("icon-lore"));
        List<String> subIconLore = parseStringList(m.get("sub-icon-lore"));
        return new NamespaceEntry(id, visibility, permission, iconName, itemLore, iconLore, subIconLore,
                parseHiddenItems(m.get("hidden-items")), parseHiddenCategories(m.get("hidden-categories")));
    }

    private ButtonEntry parseButton(Map<?, ?> m) {
        String id = str(m.get("id"));
        Map<?, ?> displayMap = (Map<?, ?>) m.get("display");
        String material = str(displayMap.get("material"));
        String name = str(displayMap.get("name"));
        List<String> lore = new ArrayList<>();
        if (displayMap.get("lore") instanceof List<?> loreList) {
            for (Object lo : loreList) {
                lore.add(str(lo));
            }
        }
        ButtonDisplay display = new ButtonDisplay(material, name, lore);
        Map<String, List<String>> actions = parseActions(m.get("actions"));
        Visibility visibility = parseButtonVisibility(m.get("visibility"));
        String permission = str(m.get("permission"));
        return new ButtonEntry(id, display, actions, visibility, permission);
    }

    private Map<String, List<String>> parseActions(Object actionsObj) {
        Map<String, List<String>> actions = new LinkedHashMap<>();
        if (actionsObj instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> ae : am.entrySet()) {
                String key = str(ae.getKey()).toLowerCase(Locale.ROOT);
                List<String> list = new ArrayList<>();
                if (ae.getValue() instanceof List<?> l) {
                    for (Object s : l) {
                        list.add(str(s));
                    }
                }
                actions.put(key, list);
            }
        } else if (actionsObj instanceof List<?> al) {
            List<String> list = new ArrayList<>();
            for (Object s : al) {
                list.add(str(s));
            }
            actions.put("all", list);
        }
        return actions;
    }

    /**
     * 将 CraftEngine 中存在、但未在 categories.yml 中配置的命名空间写入 categories-unapplied.yml。
     * 保留 categories-unapplied.yml 中已有的自定义（可见性/悬浮提示），仅追加新出现的命名空间（默认仅管理员可见）。
     */
    public void sync(Collection<String> namespaces) {
        Set<String> inCategories = new LinkedHashSet<>();
        for (Entry e : this.entries) {
            if (e instanceof NamespaceEntry n) {
                inCategories.add(n.id());
            }
        }
        Map<String, NamespaceEntry> existing = new LinkedHashMap<>();
        for (NamespaceEntry n : this.extra) {
            existing.put(n.id(), n);
        }
        List<NamespaceEntry> next = new ArrayList<>();
        for (String ns : namespaces) {
            if (inCategories.contains(ns)) continue;
            NamespaceEntry e = existing.get(ns);
            if (e == null) {
                e = new NamespaceEntry(ns, Visibility.ADMIN, "", "", List.of(), List.of(), List.of(), Map.of(), Set.of());
            }
            next.add(e);
        }
        this.namespaceIndex.clear();
        for (Entry entry : this.entries) {
            if (entry instanceof NamespaceEntry ns) this.namespaceIndex.putIfAbsent(ns.id(), ns);
        }
        for (NamespaceEntry ns : next) this.namespaceIndex.putIfAbsent(ns.id(), ns);
        boolean extraChanged = !next.equals(this.extra);
        this.extra.clear();
        this.extra.addAll(next);
        this.rebuildFilters();
        if (extraChanged) {
            this.saveExtra();
        }
        if (!this.file.exists()) {
            this.save();
        }
    }

    public List<String> orderedNamespaces() {
        List<String> out = new ArrayList<>();
        for (Entry e : this.allEntries()) {
            if (e instanceof NamespaceEntry n) {
                out.add(n.id());
            }
        }
        return out;
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public List<Entry> allEntries() {
        List<Entry> out = new ArrayList<>(this.entries);
        out.addAll(this.extra);
        return out;
    }

    public List<NamespaceEntry> unappliedEntries() {
        return List.copyOf(this.extra);
    }

    public NamespaceEntry findNamespace(String ns) {
        return this.namespaceIndex.get(ns);
    }

    public boolean canView(Player player, String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        if (e == null) {
            return false;
        }
        if (isAdmin(player)) {
            return true;
        }
        return switch (e.visibility()) {
            case PLAYER -> true;
            case ADMIN -> false;
            case PERMISSION -> e.permission() != null && !e.permission().isBlank() && player.hasPermission(e.permission());
        };
    }

    public boolean canViewButton(Player player, ButtonEntry b) {
        if (isAdmin(player)) {
            return true;
        }
        return switch (b.visibility()) {
            case PLAYER -> true;
            case ADMIN -> false;
            case PERMISSION -> b.permission() != null && !b.permission().isBlank() && player.hasPermission(b.permission());
        };
    }

    public Visibility visibilityOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? Visibility.ADMIN : e.visibility();
    }

    public String permissionOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? "" : e.permission();
    }

    public String iconNameOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? "" : e.iconName();
    }

    public List<String> itemLoreOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? List.of() : e.itemLore();
    }

    public List<String> iconLoreOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? List.of() : e.iconLore();
    }

    public List<String> subIconLoreOf(String namespace) {
        NamespaceEntry e = findNamespace(namespace);
        return e == null ? List.of() : e.subIconLore();
    }

    public Set<String> hiddenItems(String categoryId) {
        return this.hiddenItems.get(categoryId);
    }

    public boolean isCategoryHidden(String categoryId) {
        return this.hiddenCategories.contains(categoryId);
    }

    public static boolean isAdmin(Player player) {
        return player != null && (player.isOp() || player.hasPermission("craftenginerecipe.admin"));
    }

    private static Visibility parseVisibility(String v) {
        if (v == null) {
            return Visibility.ADMIN;
        }
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "player", "all", "public" -> Visibility.PLAYER;
            case "permission", "perm" -> Visibility.PERMISSION;
            default -> Visibility.ADMIN;
        };
    }

    private static Visibility parseButtonVisibility(Object o) {
        String v = str(o).trim();
        if (v.isEmpty()) {
            return Visibility.PLAYER;
        }
        return parseVisibility(v);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static List<String> parseStringList(Object o) {
        List<String> list = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object s : l) {
                list.add(str(s));
            }
        }
        return list;
    }

    private static Map<String, Object> toMap(NamespaceEntry n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("visibility", n.visibility().name().toLowerCase(Locale.ROOT));
        m.put("permission", n.permission() == null ? "" : n.permission());
        if (n.iconName() != null && !n.iconName().isBlank()) {
            m.put("icon-name", n.iconName());
        }
        if (!n.itemLore().isEmpty()) {
            m.put("item-lore", new ArrayList<>(n.itemLore()));
        }
        if (!n.iconLore().isEmpty()) {
            m.put("icon-lore", new ArrayList<>(n.iconLore()));
        }
        if (!n.subIconLore().isEmpty()) {
            m.put("sub-icon-lore", new ArrayList<>(n.subIconLore()));
        }
        if (!n.hiddenItems().isEmpty()) {
            m.put("hidden-items", hiddenItemsToList(n.hiddenItems()));
        }
        if (!n.hiddenCategories().isEmpty()) {
            m.put("hidden-categories", new ArrayList<>(n.hiddenCategories()));
        }
        return m;
    }

    private static List<Map<String, Object>> hiddenItemsToList(Map<String, Set<String>> rules) {
        List<Map<String, Object>> list = new ArrayList<>();
        rules.forEach((category, items) -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("category", category);
            rule.put("items", new ArrayList<>(items));
            list.add(rule);
        });
        return list;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header(this.language.text("headers.categories"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : this.entries) {
            if (e instanceof NamespaceEntry n) {
                list.add(toMap(n));
            } else if (e instanceof ButtonEntry b) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (!b.id().isBlank()) {
                    m.put("id", b.id());
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("material", b.display().material());
                d.put("name", b.display().name());
                if (!b.display().lore().isEmpty()) {
                    d.put("lore", new ArrayList<>(b.display().lore()));
                }
                m.put("display", d);
                if (b.visibility() != Visibility.PLAYER) {
                    m.put("visibility", b.visibility().name().toLowerCase(Locale.ROOT));
                }
                if (b.permission() != null && !b.permission().isBlank()) {
                    m.put("permission", b.permission());
                }
                if (!b.actions().isEmpty()) {
                    Map<String, Object> am = new LinkedHashMap<>();
                    for (Map.Entry<String, List<String>> ae : b.actions().entrySet()) {
                        am.put(ae.getKey(), new ArrayList<>(ae.getValue()));
                    }
                    m.put("actions", am);
                }
                list.add(m);
            }
        }
        yaml.set("entries", list);
        if (!this.legacyHiddenItems.isEmpty()) {
            yaml.set("hidden-items", hiddenItemsToList(this.legacyHiddenItems));
        }
        try {
            yaml.save(this.file);
        } catch (IOException e) {
            this.logger.severe(this.language.text("logs.category-save-failed", "error", e.getMessage()));
        }
    }

    private void saveExtra() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header(this.language.text("headers.unapplied"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (NamespaceEntry n : this.extra) {
            list.add(toMap(n));
        }
        yaml.set("entries", list);
        try {
            yaml.save(this.extraFile);
        } catch (IOException e) {
            this.logger.severe(this.language.text("logs.unapplied-save-failed", "error", e.getMessage()));
        }
    }
}

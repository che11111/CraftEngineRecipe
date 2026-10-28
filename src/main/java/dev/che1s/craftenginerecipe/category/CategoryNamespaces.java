package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.regex.Pattern;

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
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern FULL_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern ID_PATH = Pattern.compile("[a-z0-9_./-]+");

    public enum Visibility {
        ADMIN,
        PLAYER,
        PERMISSION
    }

    public sealed interface Entry {
    }

    public record NamespaceEntry(
            String id,
            Visibility visibility,
            String permission,
            boolean categoryEntry,
            String iconName,
            List<String> itemLore,
            List<String> iconLore,
            List<String> subIconLore,
            boolean sortByNamespace,
            boolean hideItemsWithoutRecipes,
            Map<String, Set<String>> hiddenItems,
            Set<String> hiddenCategories,
            boolean hiddenRulesAffectWorkstations,
            boolean hideInaccessible) implements Entry {
        public NamespaceEntry {
            itemLore = immutableList(itemLore);
            iconLore = immutableList(iconLore);
            subIconLore = immutableList(subIconLore);
            Map<String, Set<String>> rules = new LinkedHashMap<>();
            hiddenItems.forEach((category, items) ->
                    rules.put(category, Collections.unmodifiableSet(new LinkedHashSet<>(items))));
            hiddenItems = Collections.unmodifiableMap(rules);
            hiddenCategories = Collections.unmodifiableSet(new LinkedHashSet<>(hiddenCategories));
        }
    }

    public record ButtonEntry(ButtonDisplay display, Map<String, List<String>> actions, Visibility visibility,
                              String permission, boolean hideInaccessible) implements Entry {
        public ButtonEntry {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            actions.forEach((click, lines) -> copied.put(click, immutableList(lines)));
            actions = Collections.unmodifiableMap(copied);
        }

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
        public ButtonDisplay {
            lore = immutableList(lore);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        // 保持顺序及 null 动作项与现有动作执行器兼容。
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private final LanguageManager language;
    private final Logger logger;
    private final File file;
    private final File extraFile;
    private final File targetFile;
    private final File targetExtraFile;
    private final Map<String, NamespaceEntry> namespaceIndex = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> extra = new ArrayList<>();
    private final Map<String, Set<String>> hiddenItems = new LinkedHashMap<>();
    private final Set<String> hiddenCategories = new LinkedHashSet<>();
    private final Map<String, Set<String>> workstationHiddenItems = new LinkedHashMap<>();
    private final Set<String> workstationHiddenCategories = new LinkedHashSet<>();
    private final List<CategoryReference> categoryReferences = new ArrayList<>();

    private record CategoryReference(File source, String path, String id) {}

    public CategoryNamespaces(CraftEngineRecipePlugin plugin) {
        this(plugin.getDataFolder(), plugin.lang(), plugin.getLogger());
    }

    CategoryNamespaces(File dataFolder, LanguageManager language, Logger logger) {
        this.language = language;
        this.logger = logger;
        this.targetFile = new File(dataFolder, "navigation/categories.yml");
        File legacyFile = new File(dataFolder, "categories.yml");
        this.file = this.targetFile.exists() || !legacyFile.exists() ? this.targetFile : legacyFile;
        this.targetExtraFile = new File(dataFolder, "navigation/categories-unapplied.yml");
        File legacyExtraFile = new File(dataFolder, "categories-unapplied.yml");
        this.extraFile = this.targetExtraFile.exists() || !legacyExtraFile.exists()
                ? this.targetExtraFile : legacyExtraFile;
    }

    public void load() {
        this.namespaceIndex.clear();
        this.entries.clear();
        this.extra.clear();
        this.hiddenItems.clear();
        this.hiddenCategories.clear();
        this.workstationHiddenItems.clear();
        this.workstationHiddenCategories.clear();
        this.categoryReferences.clear();
        if (this.file.exists()) {
            this.loadCategoriesFile();
        }
        if (this.extraFile.exists()) {
            this.loadExtraFile();
        }
        this.rebuildFilters();
        if (!this.extraFile.equals(this.targetExtraFile) && this.extraFile.exists()
                && !this.targetExtraFile.exists()) {
            this.saveExtra();
        }
    }

    private void loadCategoriesFile() {
        YamlConfiguration yaml = readYaml(this.file);
        List<?> list = yaml.getList("entries");
        String listPath = "entries";
        if (list == null) {
            list = yaml.getList("namespaces");
            listPath = "namespaces";
        }
        if (list != null) {
            for (int index = 0; index < list.size(); index++) {
                Object o = list.get(index);
                if (!(o instanceof Map<?, ?> m)) continue;
                this.diagnoseEntry(m, this.file, listPath + "[" + index + "]");
                if (m.get("display") instanceof Map<?, ?>) {
                    ButtonEntry button = parseButtonEntry(m);
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
        this.workstationHiddenItems.clear();
        this.workstationHiddenCategories.clear();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : this.allEntries()) {
            if (entry instanceof NamespaceEntry namespace && seen.add(namespace.id())) {
                mergeHiddenItems(this.hiddenItems, namespace.hiddenItems());
                this.hiddenCategories.addAll(namespace.hiddenCategories());
                if (namespace.hiddenRulesAffectWorkstations()) {
                    mergeHiddenItems(this.workstationHiddenItems, namespace.hiddenItems());
                    this.workstationHiddenCategories.addAll(namespace.hiddenCategories());
                }
            }
        }
        this.hiddenItems.replaceAll((category, items) -> Set.copyOf(items));
        this.workstationHiddenItems.replaceAll((category, items) -> Set.copyOf(items));
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
        for (int index = 0; index < list.size(); index++) {
            Object o = list.get(index);
            if (!(o instanceof Map<?, ?> m)) continue;
            this.diagnoseEntry(m, this.extraFile, "entries[" + index + "]");
            if (m.get("display") instanceof Map<?, ?>) {
                ButtonEntry button = parseButtonEntry(m);
                if (button != null) this.extra.add(button);
            } else {
                NamespaceEntry namespace = parseNamespace(m);
                if (namespace != null) this.extra.add(namespace);
            }
        }
    }

    private void diagnoseEntry(Map<?, ?> entry, File source, String path) {
        if (entry.get("display") instanceof Map<?, ?> display) {
            String material = str(display.get("material"));
            if (material.isBlank()) return; // 空材质会刻意沿用现有的石头回退值。
            String prefix = "source:CraftEngine:";
            if (material.startsWith(prefix)) {
                this.diagnoseId(material.substring(prefix.length()), source, path + ".display.material");
            } else if (!FULL_ID.matcher(material).matches() && !ID_PATH.matcher(material).matches()
                    && Material.matchMaterial(material) == null) {
                this.warn("logs.config-invalid-material", source, path + ".display.material", material);
            }
        } else {
            this.diagnoseNamespace(entry, source, path);
        }
    }

    private void diagnoseNamespace(Map<?, ?> entry, File source, String path) {
        String id = str(entry.get("id"));
        if (!NAMESPACE.matcher(id).matches()) this.warn("logs.config-invalid-id", source, path + ".id", id);
        this.diagnoseHiddenItems(entry.get("hidden-items"), source, path + ".hidden-items");
        if (entry.get("hidden-categories") instanceof List<?> categories) {
            for (int index = 0; index < categories.size(); index++) {
                this.diagnoseCategory(str(categories.get(index)), source, path + ".hidden-categories[" + index + "]");
            }
        }
    }

    private void diagnoseHiddenItems(Object value, File source, String path) {
        if (!(value instanceof List<?> rules)) return;
        for (int index = 0; index < rules.size(); index++) {
            if (!(rules.get(index) instanceof Map<?, ?> rule)) continue;
            String rulePath = path + "[" + index + "]";
            this.diagnoseCategory(str(rule.get("category")), source, rulePath + ".category");
            if (rule.get("items") instanceof List<?> items) {
                for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                    String item = str(items.get(itemIndex));
                    if (!item.equals("*")) this.diagnoseId(item, source, rulePath + ".items[" + itemIndex + "]");
                }
            }
        }
    }

    private void diagnoseId(String value, File source, String path) {
        if (!FULL_ID.matcher(value).matches()) this.warn("logs.config-invalid-id", source, path, value);
    }

    private void diagnoseCategory(String value, File source, String path) {
        this.diagnoseId(value, source, path);
        this.categoryReferences.add(new CategoryReference(source, path, value));
    }

    private void warn(String message, File source, String path, String value) {
        this.logger.warning(this.language.text(message, "path", source.getName() + ":" + path, "value", value));
    }

    /** 在 CraftEngine 分类快照就绪后发出警告；不得删除或改写已配置的规则。 */
    public void validateReferences(Set<Key> categories) {
        Set<String> known = new LinkedHashSet<>();
        for (Key category : categories) known.add(category.toString());
        for (CategoryReference reference : this.categoryReferences) {
            if (FULL_ID.matcher(reference.id()).matches() && !known.contains(reference.id())) {
                this.warn("logs.config-missing-category", reference.source(), reference.path(), reference.id());
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
        return new NamespaceEntry(id, visibility, permission, parseBoolean(m.get("category-entry"), true),
                iconName, itemLore, iconLore, subIconLore,
                parseBoolean(m.get("sort-by-namespace"), false),
                parseBoolean(m.get("hide-items-without-recipes"), false),
                parseHiddenItems(m.get("hidden-items")), parseHiddenCategories(m.get("hidden-categories")),
                parseBoolean(m.get("hidden-rules-affect-workstations"), true),
                parseBoolean(m.get("hide-inaccessible"), false));
    }

    public static ButtonEntry parseButtonEntry(Map<?, ?> m) {
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
        return new ButtonEntry(display, actions, visibility, permission,
                parseBoolean(m.get("hide-inaccessible"), false));
    }

    private static Map<String, List<String>> parseActions(Object actionsObj) {
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
        for (Entry value : this.extra) {
            if (value instanceof NamespaceEntry namespace) existing.put(namespace.id(), namespace);
        }
        Set<String> availableNamespaces = Set.copyOf(namespaces);
        List<Entry> next = new ArrayList<>();
        Set<String> retainedNamespaces = new LinkedHashSet<>();
        for (Entry entry : this.extra) {
            if (entry instanceof ButtonEntry) {
                next.add(entry);
            } else if (entry instanceof NamespaceEntry namespace
                    && !inCategories.contains(namespace.id())
                    && availableNamespaces.contains(namespace.id())
                    && retainedNamespaces.add(namespace.id())) {
                next.add(namespace);
            }
        }
        for (String namespace : namespaces) {
            if (inCategories.contains(namespace) || !retainedNamespaces.add(namespace)) continue;
            NamespaceEntry entry = existing.get(namespace);
            next.add(entry == null ? new NamespaceEntry(namespace, Visibility.ADMIN, "", true,
                    "", List.of(), List.of(), List.of(), false, false, Map.of(), Set.of(), true, false)
                    : entry);
        }
        this.namespaceIndex.clear();
        for (Entry entry : this.entries) {
            if (entry instanceof NamespaceEntry ns) this.namespaceIndex.putIfAbsent(ns.id(), ns);
        }
        for (Entry entry : next) {
            if (entry instanceof NamespaceEntry namespace) this.namespaceIndex.putIfAbsent(namespace.id(), namespace);
        }
        boolean extraChanged = !next.equals(this.extra);
        this.extra.clear();
        this.extra.addAll(next);
        this.rebuildFilters();
        if (extraChanged) {
            this.saveExtra();
        }
        if (!this.targetFile.exists()) {
            this.save();
        }
    }

    public synchronized List<String> orderedNamespaces() {
        List<String> out = new ArrayList<>();
        for (Entry e : this.allEntries()) {
            if (e instanceof NamespaceEntry n) {
                out.add(n.id());
            }
        }
        return out;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(this.entries);
    }

    public synchronized List<Entry> allEntries() {
        List<Entry> out = new ArrayList<>(this.entries);
        out.addAll(this.extra);
        return List.copyOf(out);
    }

    public synchronized List<NamespaceEntry> unappliedEntries() {
        return this.extra.stream()
                .filter(NamespaceEntry.class::isInstance)
                .map(NamespaceEntry.class::cast)
                .toList();
    }

    public synchronized List<Entry> unappliedAllEntries() {
        return List.copyOf(this.extra);
    }

    public synchronized NamespaceEntry unappliedEntry(String namespace) {
        for (Entry entry : this.extra) {
            if (entry instanceof NamespaceEntry namespaceEntry && namespaceEntry.id().equals(namespace)) {
                return namespaceEntry;
            }
        }
        return null;
    }

    public synchronized NamespaceEntry appliedEntry(String namespace) {
        for (Entry entry : this.entries) {
            if (entry instanceof NamespaceEntry namespaceEntry && namespaceEntry.id().equals(namespace)) {
                return namespaceEntry;
            }
        }
        return null;
    }

    public synchronized boolean isApplied(String namespace) {
        return this.appliedEntry(namespace) != null;
    }

    /**
     * 更新当前加载快照中的正式或未应用命名空间条目，并只写回其来源文件。
     */
    public synchronized boolean updateNamespace(String namespace, NamespaceEntry updated) {
        if (namespace == null || updated == null || !namespace.equals(updated.id())) return false;
        for (int index = 0; index < this.entries.size(); index++) {
            Entry current = this.entries.get(index);
            if (!(current instanceof NamespaceEntry entry) || !entry.id().equals(namespace)) continue;
            this.entries.set(index, updated);
            this.namespaceIndex.put(namespace, updated);
            this.rebuildFilters();
            this.save();
            return true;
        }
        for (int index = 0; index < this.extra.size(); index++) {
            Entry value = this.extra.get(index);
            if (!(value instanceof NamespaceEntry current) || !current.id().equals(namespace)) continue;
            this.extra.set(index, updated);
            this.namespaceIndex.put(namespace, updated);
            this.rebuildFilters();
            this.saveExtra();
            return true;
        }
        return false;
    }

    /** 兼容旧调用：只更新命中文件中的未应用条目，不触碰正式 entries。 */
    public synchronized boolean updateUnapplied(String namespace, Visibility visibility, String permission,
                                                 boolean categoryEntry, String iconName, boolean sortByNamespace,
                                                 boolean hideItemsWithoutRecipes,
                                                 boolean hiddenRulesAffectWorkstations) {
        for (int index = 0; index < this.extra.size(); index++) {
            Entry value = this.extra.get(index);
            if (!(value instanceof NamespaceEntry current) || !current.id().equals(namespace)) continue;
            NamespaceEntry updated = new NamespaceEntry(current.id(), visibility, permission,
                    categoryEntry, iconName, current.itemLore(), current.iconLore(), current.subIconLore(),
                    sortByNamespace, hideItemsWithoutRecipes, current.hiddenItems(), current.hiddenCategories(),
                    hiddenRulesAffectWorkstations, current.hideInaccessible());
            return this.updateNamespace(namespace, updated);
        }
        return false;
    }

    /**
     * 将未应用条目加入 categories.yml 末尾。加入后仍可通过管理菜单调整位置。
     */
    public synchronized boolean applyUnapplied(String namespace) {
        if (this.appliedEntry(namespace) != null) return false;
        for (int index = 0; index < this.extra.size(); index++) {
            Entry value = this.extra.get(index);
            if (!(value instanceof NamespaceEntry entry) || !entry.id().equals(namespace)) continue;
            this.extra.remove(index);
            this.entries.add(entry);
            this.namespaceIndex.put(namespace, entry);
            this.rebuildFilters();
            this.save();
            this.saveExtra();
            return true;
        }
        return false;
    }

    /** 将任意未应用条目追加到 categories.yml；索引对应 {@link #unappliedAllEntries()}。 */
    public synchronized boolean applyUnappliedEntry(int index) {
        if (index < 0 || index >= this.extra.size()) return false;
        Entry entry = this.extra.remove(index);
        this.entries.add(entry);
        if (entry instanceof NamespaceEntry namespace) this.namespaceIndex.put(namespace.id(), namespace);
        this.rebuildFilters();
        this.save();
        this.saveExtra();
        return true;
    }

    /**
     * 将任意正式条目移动到 categories-unapplied.yml 末尾，并保留其完整配置。
     * 索引对应 {@link #entries()}。
     */
    public synchronized boolean unapplyEntry(int index) {
        if (index < 0 || index >= this.entries.size()) return false;
        Entry entry = this.entries.get(index);
        if (entry instanceof NamespaceEntry namespace && this.unappliedEntry(namespace.id()) != null) return false;
        this.entries.remove(index);
        this.extra.add(entry);
        this.rebuildFilters();
        this.save();
        this.saveExtra();
        return true;
    }

    /**
     * 调整正式 entries 的顺序；索引对应 {@link #entries()}。
     */
    public synchronized boolean moveEntry(int index, int offset) {
        if (index < 0 || index >= this.entries.size() || offset == 0) return false;
        int target = index + offset;
        if (target < 0 || target >= this.entries.size()) return false;
        Entry entry = this.entries.remove(index);
        this.entries.add(target, entry);
        this.rebuildFilters();
        this.save();
        return true;
    }

    /** 更新 categories.yml 中的自定义按钮；索引对应 {@link #entries()}。 */
    public synchronized boolean updateButton(int index, ButtonEntry updated) {
        if (index < 0 || index >= this.entries.size() || updated == null) return false;
        if (!(this.entries.get(index) instanceof ButtonEntry)) return false;
        this.entries.set(index, updated);
        this.save();
        return true;
    }

    /** 更新 categories-unapplied.yml 中的自定义按钮；索引对应 {@link #unappliedAllEntries()}。 */
    public synchronized boolean updateUnappliedButton(int index, ButtonEntry updated) {
        if (index < 0 || index >= this.extra.size() || updated == null) return false;
        if (!(this.extra.get(index) instanceof ButtonEntry)) return false;
        this.extra.set(index, updated);
        this.saveExtra();
        return true;
    }

    /**
     * 切换某个分类下单个物品的隐藏状态。取消隐藏“全部物品”规则时，
     * 仅恢复指定物品，其余直接物品继续隐藏。
     */
    public synchronized boolean setItemHidden(String namespace, String categoryId, String itemId,
                                              boolean hidden, Collection<String> directItems) {
        NamespaceEntry current = this.findNamespace(namespace);
        if (current == null || categoryId == null || categoryId.isBlank() || itemId == null || itemId.isBlank()) {
            return false;
        }
        Map<String, Set<String>> rules = copyRules(current.hiddenItems());
        Set<String> items = new LinkedHashSet<>(rules.getOrDefault(categoryId, Set.of()));
        if (hidden) {
            items.add(itemId);
        } else if (items.remove("*")) {
            items.clear();
            if (directItems != null) {
                for (String direct : directItems) {
                    if (direct != null && !direct.isBlank() && !direct.equals(itemId)) items.add(direct);
                }
            }
        } else {
            items.remove(itemId);
        }
        putRule(rules, categoryId, items);
        return this.updateHiddenState(current, rules, current.hiddenCategories());
    }

    /** 隐藏或显示分类中的全部直接物品；不影响子分类入口。 */
    public synchronized boolean setAllItemsHidden(String namespace, String categoryId, boolean hidden) {
        NamespaceEntry current = this.findNamespace(namespace);
        if (current == null || categoryId == null || categoryId.isBlank()) return false;
        Map<String, Set<String>> rules = copyRules(current.hiddenItems());
        if (hidden) {
            LinkedHashSet<String> items = new LinkedHashSet<>(rules.getOrDefault(categoryId, Set.of()));
            items.add("*");
            rules.put(categoryId, items);
        } else {
            rules.remove(categoryId);
        }
        return this.updateHiddenState(current, rules, current.hiddenCategories());
    }

    /** 隐藏分类入口及经过该分类的全部浏览路径。 */
    public synchronized boolean setCategoryHidden(String namespace, String categoryId, boolean hidden) {
        NamespaceEntry current = this.findNamespace(namespace);
        if (current == null || categoryId == null || categoryId.isBlank()) return false;
        Set<String> categories = new LinkedHashSet<>(current.hiddenCategories());
        if (hidden) categories.add(categoryId);
        else categories.remove(categoryId);
        return this.updateHiddenState(current, current.hiddenItems(), categories);
    }

    private boolean updateHiddenState(NamespaceEntry current, Map<String, Set<String>> rules,
                                      Set<String> categories) {
        NamespaceEntry updated = new NamespaceEntry(current.id(), current.visibility(), current.permission(),
                current.categoryEntry(), current.iconName(), current.itemLore(), current.iconLore(),
                current.subIconLore(), current.sortByNamespace(), current.hideItemsWithoutRecipes(),
                rules, categories, current.hiddenRulesAffectWorkstations(), current.hideInaccessible());
        return this.updateNamespace(current.id(), updated);
    }

    private static Map<String, Set<String>> copyRules(Map<String, Set<String>> source) {
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        source.forEach((category, items) -> copied.put(category, new LinkedHashSet<>(items)));
        return copied;
    }

    private static void putRule(Map<String, Set<String>> rules, String category, Set<String> items) {
        if (items.isEmpty()) rules.remove(category);
        else rules.put(category, items);
    }

    public synchronized NamespaceEntry findNamespace(String ns) {
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

    public synchronized Set<String> hiddenItems(String categoryId) {
        return this.hiddenItems.get(categoryId);
    }

    public synchronized boolean isCategoryHidden(String categoryId) {
        return this.hiddenCategories.contains(categoryId);
    }

    public synchronized Set<String> hiddenItemsForWorkstations(String categoryId) {
        return this.workstationHiddenItems.getOrDefault(categoryId, Set.of());
    }

    public synchronized boolean isCategoryHiddenForWorkstations(String categoryId) {
        return this.workstationHiddenCategories.contains(categoryId);
    }

    /** 显式过滤器可以指向未出现在 CE 顶层浏览列表中的分类。 */
    public synchronized Set<String> filteredCategoryIds() {
        Set<String> ids = new LinkedHashSet<>(this.hiddenCategories);
        ids.addAll(this.hiddenItems.keySet());
        return Set.copyOf(ids);
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

    private static boolean parseBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        String text = str(value).trim();
        if (text.isEmpty()) return fallback;
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        return fallback;
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
        if (!n.categoryEntry()) {
            m.put("category-entry", false);
        }
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
        if (n.sortByNamespace()) {
            m.put("sort-by-namespace", true);
        }
        if (n.hideItemsWithoutRecipes()) {
            m.put("hide-items-without-recipes", true);
        }
        if (!n.hiddenItems().isEmpty()) {
            m.put("hidden-items", hiddenItemsToList(n.hiddenItems()));
        }
        if (!n.hiddenCategories().isEmpty()) {
            m.put("hidden-categories", new ArrayList<>(n.hiddenCategories()));
        }
        if (!n.hiddenRulesAffectWorkstations()) {
            m.put("hidden-rules-affect-workstations", false);
        }
        if (n.hideInaccessible()) {
            m.put("hide-inaccessible", true);
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
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : this.entries) {
            if (e instanceof NamespaceEntry n) {
                list.add(toMap(n));
            } else if (e instanceof ButtonEntry b) {
                list.add(buttonMap(b));
            }
        }
        yaml.set("entries", list);
        try {
            writeYaml(yaml, this.targetFile.toPath());
        } catch (IOException e) {
            this.logger.severe(this.language.text("logs.category-save-failed", "error", e.getMessage()));
        }
    }

    public static Map<String, Object> buttonMap(ButtonEntry button) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("material", button.display().material());
        display.put("name", button.display().name());
        if (!button.display().lore().isEmpty()) {
            display.put("lore", new ArrayList<>(button.display().lore()));
        }
        map.put("display", display);
        if (button.visibility() != Visibility.PLAYER) {
            map.put("visibility", button.visibility().name().toLowerCase(Locale.ROOT));
        }
        if (button.permission() != null && !button.permission().isBlank()) {
            map.put("permission", button.permission());
        }
        if (button.hideInaccessible()) {
            map.put("hide-inaccessible", true);
        }
        if (!button.actions().isEmpty()) {
            Map<String, Object> actions = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : button.actions().entrySet()) {
                actions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            map.put("actions", actions);
        }
        return map;
    }

    private void saveExtra() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header(this.language.text("headers.unapplied"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : this.extra) {
            if (entry instanceof NamespaceEntry namespace) list.add(toMap(namespace));
            else if (entry instanceof ButtonEntry button) list.add(buttonMap(button));
        }
        yaml.set("entries", list);
        try {
            writeYaml(yaml, this.targetExtraFile.toPath());
        } catch (IOException e) {
            this.logger.severe(this.language.text("logs.unapplied-save-failed", "error", e.getMessage()));
        }
    }

    /** 保留上一次文件为 .bak，并以同目录临时文件原子替换，避免保存中断损坏配置。 */
    private static void writeYaml(YamlConfiguration yaml, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.isRegularFile(target)) {
            Path backup = target.resolveSibling(target.getFileName() + ".bak");
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

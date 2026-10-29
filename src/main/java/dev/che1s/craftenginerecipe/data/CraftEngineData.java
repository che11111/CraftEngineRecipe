package dev.che1s.craftenginerecipe.data;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryGraph;
import dev.che1s.craftenginerecipe.category.CategoryIndex;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.recipe.RecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.SearchIndex;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.ResourcePackTranslations;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import dev.che1s.craftenginerecipe.util.VanillaTranslations;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 捕获 CraftEngine 的分类、物品、配方和资源包翻译，并发布不可变快照。
 *
 * <p>重建分阶段执行：全局线程读取 Bukkit/CraftEngine 管理器，工作线程仅处理
 * 纯数据索引，最后回到全局线程发布结果。{@link #generation} 用于丢弃重载期间
 * 已经过期的异步回调，避免旧数据覆盖新数据。</p>
 */
public final class CraftEngineData implements Listener {
    public static final Key ALL_ITEMS_CATEGORY = Key.of("internal:all");
    private final CraftEngineRecipePlugin plugin;
    private final CategoryNamespaces emptyNamespaces;
    private static final CategoryGraph EMPTY_GRAPH = CategoryGraph.compile(Map.of());
    private static final Pattern MEMBER_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+");
    private final AtomicLong reloadRevision = new AtomicLong();
    private volatile Snapshot snapshot;
    private volatile Map<String, String> resourcePackTranslations = Map.of();
    private volatile long translationRevision;
    private volatile boolean ready;
    private volatile long generation;
    private volatile boolean stopped;
    private ScheduledTask pendingTask;
    private ScheduledTask indexTask;
    private Consumer<Boolean> completion;

    public CraftEngineData(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.emptyNamespaces = new CategoryNamespaces(plugin);
    }

    public void attachListener() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void detachListener() {
        this.stopped = true;
        ++this.generation;
        this.ready = false;
        this.cancelTasks();
        this.completion = null;
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        this.reloadRevision.incrementAndGet();
        // 配置读取属于全局任务；查看者背包只能在各自的实体调度器上访问。
        this.plugin.tasks().global(() -> this.rebuild(ok -> {
            if (ok) this.plugin.externalRecipes().reexportAfterCraftEngineReload();
        }));
    }

    public void rebuild(Consumer<Boolean> callback) { this.rebuild(false, callback); }

    /**
     * 启动一次完整重建。调用方必须位于全局调度线程。
     *
     * <p>新版本号会在关闭旧菜单前生效，任何旧回调都会因 {@link #isCurrent(long)}
     * 失败而退出。</p>
     */
    public void rebuild(boolean reloadSettings, Consumer<Boolean> callback) {
        if (!Bukkit.isGlobalTickThread()) throw new IllegalStateException("rebuild must run on the global scheduler");
        if (this.stopped) return;
        this.ready = false;
        this.cancelTasks();
        this.finish(false); // 通知已被替代的请求，避免其一直处于等待状态
        long version = ++this.generation;
        long started = System.nanoTime();
        this.completion = callback;
        this.plugin.closeAllBrowsers().whenComplete((ignored, error) -> {
            if (!this.isCurrent(version) || !this.plugin.isEnabled()) return;
            this.plugin.tasks().global(() -> {
                if (!this.isCurrent(version)) return;
                if (error != null) this.fail(version, error);
                else this.capture(version, started, reloadSettings);
            });
        });
    }

    private void capture(long version, long started, boolean reloadSettings) {
        try {
            if (reloadSettings) this.plugin.reloadSettings();
            if (CraftEngine.instance().isReloading()) {
                // CE 重载期间其管理器只处于半完成状态，等下一个全局 tick 再试。
                this.retryCapture(version, started);
                return;
            }
            long revision = this.reloadRevision.get();
            CategoryNamespaces namespaces = new CategoryNamespaces(this.plugin);
            namespaces.load();
            Map<Key, ItemDefinition> items = Collections.unmodifiableMap(new LinkedHashMap<>(CraftEngineItems.loadedItems()));
            Map<Key, Category> categories = this.captureCategories(namespaces);
            Map<String, Set<String>> packAuthors = PackMetadataIndex.scan(
                    PackMetadataIndex.resourcesDirectory(this.plugin.getDataFolder().toPath()));
            CategoryGraph graph = CategoryGraph.compile(categories);
            Set<Key> recipeKeys = new HashSet<>(items.keySet());
            for (Key category : categories.keySet()) {
                graph.members(category).stream().filter(member -> !member.category())
                        .forEach(member -> recipeKeys.add(member.key()));
            }
            RecipeSnapshot recipes = RecipeSnapshot.capture(CraftEngine.instance().recipeManager(), recipeKeys);
            Set<Key> referenced = new HashSet<>();
            Map<Key, Key> parents = new LinkedHashMap<>();
            for (Category category : categories.values()) {
                for (CategoryGraph.Member member : graph.members(category.id())) {
                    if (!member.category() || member.reference().isEmpty()) continue;
                    referenced.add(member.key());
                    parents.putIfAbsent(member.key(), category.id());
                }
            }
            List<Category> tops = new ArrayList<>();
            for (Category category : categories.values()) {
                if (!referenced.contains(category.id()) && !category.hidden()) tops.add(category);
            }
            tops.sort(null);
            // 分类图没有根节点时（例如存在循环）不得暴露隐藏分类。
            Set<String> discovered = new LinkedHashSet<>();
            categories.keySet().forEach(id -> discovered.add(id.namespace()));
            if (!this.stable(revision)) {
                this.retryCapture(version, started);
                return;
            }
            namespaces.sync(discovered);
            this.plugin.config().validateReferences(categories.keySet());
            namespaces.validateReferences(categories.keySet());
            this.validateMembers(categories);
            this.loadTranslations(version, started, new Captured(items, categories, graph, recipes,
                    List.copyOf(tops), Map.copyOf(parents), namespaces, packAuthors, revision));
        } catch (Exception | LinkageError e) {
            if (CraftEngine.instance().isReloading()) this.retryCapture(version, started);
            else this.fail(version, e);
        }
    }

    private boolean stable(long revision) {
        return this.reloadRevision.get() == revision && !CraftEngine.instance().isReloading();
    }

    /**
     * 校验分类 members 的格式与引用：普通成员需符合 ID 规则；以 '#' 开头的成员
     * 表示引用子分类，必须指向真实存在的分类。失败只记录警告，不阻断加载。
     */
    private void validateMembers(Map<Key, Category> categories) {
        for (Category category : categories.values()) {
            if (category.members() == null) continue;
            for (int index = 0; index < category.members().size(); index++) {
                String member = category.members().get(index);
                if (member == null || member.isBlank()) continue;
                boolean child = member.charAt(0) == '#';
                String reference = child ? member.substring(1) : member;
                String message = !MEMBER_ID.matcher(reference).matches() ? "logs.config-invalid-id"
                        : child && !categories.containsKey(Key.of(reference)) ? "logs.config-missing-category" : null;
                if (message != null) this.plugin.getLogger().warning(this.plugin.lang().text(message,
                        "path", "CraftEngine:categories[" + category.id() + "].members[" + index + "]", "value", member));
            }
        }
    }

    private void retryCapture(long version, long started) {
        if (this.isCurrent(version)) {
            // 使用全局延迟任务而不是忙等，既让出重载线程也保持 CE API 的线程约束。
            this.pendingTask = this.plugin.tasks().globalLater(() -> {
                if (this.isCurrent(version)) this.capture(version, started, false);
            }, 1);
        }
    }

    /**
     * 收集本次要纳入 CER 的全部分类：从 CE 的根分类出发沿 members 中的 '#id'
     * 引用做 BFS，另外把显式 hidden-items / hidden-categories 规则指向的分类
     * 也拉进来（它们可能不在 CE 的顶层浏览列表里）。结果按首次遇到顺序去重。
     */
    private Map<Key, Category> captureCategories(CategoryNamespaces namespaces) {
        Map<Key, Category> categories = new LinkedHashMap<>();
        var browser = CraftEngine.instance().itemBrowserManager();
        var roots = browser.categories();
        ArrayDeque<Category> queue = new ArrayDeque<>();
        if (roots != null) queue.addAll(roots);
        // 即使是脱离分类图或已隐藏的 CE 分类，也要保持 CER 的显式限制生效。
        for (String id : namespaces.filteredCategoryIds()) {
            try {
                browser.byId(Key.of(id)).ifPresent(queue::add);
            } catch (IllegalArgumentException ignored) {
                // 无效的配置 ID 会由下方的 validateReferences 检查并报告。
            }
        }
        Set<Key> attempted = new HashSet<>();
        while (!queue.isEmpty()) {
            Category category = queue.remove();
            if (categories.putIfAbsent(category.id(), category) != null) continue;
            if (category.members() == null) continue;
            for (String member : category.members()) {
                Key id = subCategory(member);
                if (id == null || categories.containsKey(id) || !attempted.add(id)) continue;
                CraftEngine.instance().itemBrowserManager().byId(id).ifPresent(queue::add);
            }
        }
        return Collections.unmodifiableMap(categories);
    }

    private static Key subCategory(String member) {
        if (member == null || !member.startsWith("#") || member.length() == 1) return null;
        try {
            return Key.of(member.substring(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void loadTranslations(long version, long started, Captured captured) {
        // 在这里解析 CraftEngine 配置；工作线程只接收路径和普通语言名称。
        Path zip = this.resourcePackPath();
        List<String> languages = this.plugin.config().searchLanguages();
        this.indexTask = this.plugin.tasks().async(() -> {
            ResourcePackTranslations.ReadResult result;
            String problem;
            try {
                result = ResourcePackTranslations.read(zip, languages);
                problem = null;
            } catch (IOException | RuntimeException e) {
                result = new ResourcePackTranslations.ReadResult(Map.of(), 0, List.of());
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            var loaded = result;
            var failure = problem;
            if (!this.isCurrent(version)) return;
            try {
                this.plugin.tasks().global(() -> {
                    if (!this.isCurrent(version)) return;
                    if (failure != null) {
                        this.plugin.getLogger().warning(this.plugin.lang().text("logs.search-pack-failed",
                                "path", zip.toString(), "error", failure));
                    } else {
                        this.plugin.getLogger().info(this.plugin.lang().text("logs.search-pack-ready",
                                "count", String.valueOf(loaded.translations().size()),
                                "files", String.valueOf(loaded.files()), "path", zip.toString()));
                        for (String skipped : loaded.problems()) {
                            this.plugin.getLogger().warning(this.plugin.lang().text("logs.search-pack-skipped", "error", skipped));
                        }
                    }
                    this.resourcePackTranslations = loaded.translations();
                    ++this.translationRevision;
                    this.captureNames(version, started, captured, loaded.translations());
                });
            } catch (IllegalPluginAccessException ignored) {
                // 插件关闭与工作线程完成发生了竞争。
            }
        });
    }

    private Path resourcePackPath() {
        Path dataFolder = this.plugin.getDataFolder().toPath().toAbsolutePath();
        Path serverRoot = dataFolder.getParent().getParent();
        try {
            // 兼容旧版资源包生成流程和较新的工作流式 CraftEngine 版本。
            Path configured = Config.resourcePackPath();
            if (configured != null) return configured.isAbsolute() ? configured : serverRoot.resolve(configured).normalize();
        } catch (RuntimeException | LinkageError ignored) {
            CompatibilityWarnings.warn("CraftEngine.resourcePackPath", ignored);
        }
        return dataFolder.resolveSibling("CraftEngine").resolve("generated/resource_pack.zip");
    }

    private void captureNames(long version, long started, Captured captured, Map<String, String> translations) {
        Map<String, String> vanillaTranslations;
        try {
            vanillaTranslations = VanillaTranslations.read(this.plugin.jarPath(),
                    this.plugin.lang().locale().toLowerCase(java.util.Locale.ROOT).startsWith("zh"));
        } catch (IOException | RuntimeException error) {
            CompatibilityWarnings.warn("VanillaTranslations.read", error);
            vanillaTranslations = Map.of();
        }
        Map<String, String> finalVanillaTranslations = vanillaTranslations;
        Iterator<Map.Entry<Key, ItemDefinition>> itemIterator = captured.items().entrySet().iterator();
        Iterator<Map.Entry<Key, Category>> categoryIterator = captured.categories().entrySet().iterator();
        Map<Key, String> itemNames = new LinkedHashMap<>();
        Map<Key, String> translationKeys = new LinkedHashMap<>();
        Map<Key, String> categoryNames = new LinkedHashMap<>();
        Runnable batch = new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(version)) return;
                if (!stable(captured.revision())) {
                    retryCapture(version, started);
                    return;
                }
                try {
                    // 每批最多占用全局线程约 2 毫秒或处理 128 项，避免大型整合包卡 tick。
                    long deadline = System.nanoTime() + 2_000_000L;
                    int remaining = 128;
                    do {
                        if (itemIterator.hasNext()) {
                            var entry = itemIterator.next();
                            itemNames.put(entry.getKey(), DisplayNames.of(entry.getValue(), translations));
                            translationKeys.put(entry.getKey(), DisplayNames.translationKeyOf(entry.getValue()));
                        } else if (categoryIterator.hasNext()) {
                            var entry = categoryIterator.next();
                            categoryNames.put(entry.getKey(), DisplayNames.of(entry.getValue(), translations));
                        } else {
                            buildIndexes();
                            return;
                        }
                    } while (--remaining > 0 && System.nanoTime() < deadline);
                    pendingTask = plugin.tasks().global(this);
                } catch (Exception | LinkageError e) {
                    fail(version, e);
                }
            }

            private void buildIndexes() {
                // 只向工作线程传递纯文本：不得在该线程访问 CraftEngine 管理器或 ItemStack。
                indexTask = plugin.tasks().async(() -> {
                    try {
                        Map<Key, String> searchNames = new LinkedHashMap<>(itemNames);
                        Map<Key, String> searchKeys = new LinkedHashMap<>(translationKeys);
                        for (Material material : Material.values()) {
                            if (!material.isItem()) continue;
                            Key id = Key.of("minecraft:" + material.getKey().getKey());
                            String vanillaName = VanillaTranslations.itemName(finalVanillaTranslations, id.toString());
                            if (vanillaName != null && !vanillaName.isBlank()) {
                                searchNames.putIfAbsent(id, vanillaName);
                            }
                            searchKeys.putIfAbsent(id, material.getTranslationKey());
                        }
                        SearchIndex search = SearchIndex.fromNames(searchNames, searchKeys);
                        CategoryIndex categorySearch = CategoryIndex.fromNames(categoryNames);
                        if (!isCurrent(version)) return;
                        plugin.tasks().global(() -> {
                            if (!isCurrent(version)) return;
                            if (!stable(captured.revision())) {
                                retryCapture(version, started);
                                return;
                            }
                            // 单次原子替换：读线程要么看到完整的旧快照，要么看到
                            // 完整的新快照，不会观察到半构建的索引。
                            snapshot = new Snapshot(captured, search, categorySearch);
                            ready = true;
                            plugin.getLogger().info(plugin.lang().text("logs.data-ready", "items", String.valueOf(captured.items().size()),
                                    "categories", String.valueOf(captured.tops().size()), "elapsed",
                                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1_000_000.0)));
                            finish(true);
                            plugin.categoryPrompts().requestPrompt();
                        });
                    } catch (IllegalPluginAccessException ignored) {
                        // 插件关闭与工作线程完成发生了竞争。
                    } catch (Exception | LinkageError e) {
                        if (isCurrent(version)) {
                            try {
                                plugin.tasks().global(() -> fail(version, e));
                            } catch (IllegalPluginAccessException ignored) {
                            }
                        }
                    }
                });
            }
        };
        this.pendingTask = this.plugin.tasks().global(batch);
    }

    private boolean isCurrent(long version) {
        // generation 与 stopped 都在主生命周期中维护；版本不符说明已有更新的重建请求。
        return !this.stopped && this.generation == version;
    }

    private void fail(long version, Throwable error) {
        if (!this.isCurrent(version)) return;
        this.ready = false;
        this.plugin.getLogger().log(Level.SEVERE, this.plugin.lang().text("logs.data-build-failed"), error);
        this.finish(false);
    }

    private void finish(boolean success) {
        Consumer<Boolean> callback = this.completion;
        this.completion = null;
        if (callback != null) callback.accept(success);
    }

    private void cancelTasks() {
        if (this.pendingTask != null) this.pendingTask.cancel();
        if (this.indexTask != null) this.indexTask.cancel();
        this.pendingTask = null;
        this.indexTask = null;
    }

    public String notReadyReason() {
        return this.plugin.lang().text("messages.not-ready-reason");
    }

    public CategoryNamespaces namespaces() {
        Snapshot state = this.snapshot;
        return state == null ? this.emptyNamespaces : state.captured().namespaces();
    }
    public long generation() { return this.generation; }

    /** 从生成的 CraftEngine 资源包读取的纯文本翻译。 */
    public String resourcePackTranslation(String key) {
        return key == null ? null : this.resourcePackTranslations.get(key);
    }

    public Map<String, String> resourcePackTranslations() {
        return this.resourcePackTranslations;
    }

    /** 解析已加载 CraftEngine 物品的原始名称组件与资源包翻译。 */
    public String itemDisplayName(String id) {
        try {
            Key key = Key.of(id);
            ItemDefinition definition = this.items().get(key);
            return definition == null ? null : DisplayNames.of(definition, this.resourcePackTranslations);
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("CraftEngineData.itemDisplayName", error);
            return null;
        }
    }

    public long translationRevision() { return this.translationRevision; }

    public boolean isReady() { return this.ready; }
    public Map<Key, ItemDefinition> items() {
        Snapshot state = this.snapshot;
        return state == null ? Map.of() : state.captured().items();
    }
    public Optional<ItemDefinition> item(Key id) { return Optional.ofNullable(this.items().get(id)); }
    public Optional<Category> category(Key id) { return Optional.ofNullable(this.categoriesById().get(id)); }
    public List<Category> topCategories() {
        Snapshot state = this.snapshot;
        return state == null ? List.of() : state.captured().tops();
    }
    public Map<Key, Category> categoriesById() {
        Snapshot state = this.snapshot;
        return state == null ? Map.of() : state.captured().categories();
    }
    public Key parentOf(Key id) {
        Snapshot state = this.snapshot;
        return state == null ? null : state.captured().parents().get(id);
    }
    public CategoryGraph categoryGraph() {
        Snapshot state = this.snapshot;
        return state == null ? EMPTY_GRAPH : state.captured().graph();
    }
    public RecipeSnapshot recipeSnapshot() {
        Snapshot state = this.snapshot;
        return state == null ? RecipeSnapshot.EMPTY : state.captured().recipes();
    }
    public SearchIndex searchIndex() {
        Snapshot state = this.snapshot;
        return state == null ? null : state.search();
    }
    public CategoryIndex categoryIndex() {
        Snapshot state = this.snapshot;
        return state == null ? null : state.categorySearch();
    }
    public Set<String> detectedNamespaces() {
        Snapshot state = this.snapshot;
        if (state == null) return Set.of();
        Set<String> namespaces = new LinkedHashSet<>();
        state.captured().categories().keySet().forEach(id -> namespaces.add(id.namespace()));
        state.captured().items().keySet().forEach(id -> namespaces.add(id.namespace()));
        return Set.copyOf(namespaces);
    }

    public Set<String> packAuthors(String namespace) {
        Snapshot state = this.snapshot;
        if (state == null || namespace == null) return Set.of();
        return state.captured().packAuthors().getOrDefault(namespace, Set.of());
    }

    private record Captured(Map<Key, ItemDefinition> items, Map<Key, Category> categories, CategoryGraph graph,
                            RecipeSnapshot recipes, List<Category> tops, Map<Key, Key> parents,
                            CategoryNamespaces namespaces, Map<String, Set<String>> packAuthors, long revision) {}

    private record Snapshot(Captured captured, SearchIndex search, CategoryIndex categorySearch) {}
}

package dev.leon.cer.data;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.util.DisplayNames;
import dev.leon.cer.util.ResourcePackTranslations;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
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
import java.util.logging.Level;
import java.io.IOException;
import java.nio.file.Path;

public final class CraftEngineData implements Listener {
    public static final Key ALL_ITEMS_CATEGORY = Key.of("internal:all");
    private final CraftEngineRecipePlugin plugin;
    private final CategoryNamespaces emptyNamespaces;
    private volatile Snapshot snapshot;
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
        // Configuration reads are global; viewer inventories belong to their entity schedulers.
        this.plugin.tasks().global(() -> this.rebuild(ok -> {}));
    }

    public void rebuild(Consumer<Boolean> callback) { this.rebuild(false, callback); }

    public void rebuild(boolean reloadSettings, Consumer<Boolean> callback) {
        if (!Bukkit.isGlobalTickThread()) throw new IllegalStateException("rebuild must run on the global scheduler");
        if (this.stopped) return;
        this.ready = false;
        this.cancelTasks();
        this.finish(false); // notify a superseded request instead of leaving it pending
        long version = ++this.generation;
        long started = System.nanoTime();
        this.completion = callback;
        this.plugin.browser().closeAll().whenComplete((ignored, error) -> {
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
            CategoryNamespaces namespaces = new CategoryNamespaces(this.plugin);
            namespaces.load();
            Map<Key, ItemDefinition> items = Collections.unmodifiableMap(new LinkedHashMap<>(CraftEngineItems.loadedItems()));
            Map<Key, Category> categories = this.captureCategories();
            Set<Key> referenced = new HashSet<>();
            Map<Key, Key> parents = new LinkedHashMap<>();
            for (Category category : categories.values()) {
                if (category.members() == null) continue;
                for (String member : category.members()) {
                    Key child = subCategory(member);
                    if (child == null) continue;
                    referenced.add(child);
                    parents.putIfAbsent(child, category.id());
                }
            }
            List<Category> tops = new ArrayList<>();
            for (Category category : categories.values()) {
                if (!referenced.contains(category.id()) && !category.hidden()) tops.add(category);
            }
            tops.sort(null);
            // Do not expose hidden categories when the graph has no roots (e.g. a cycle).
            Set<String> discovered = new LinkedHashSet<>();
            categories.keySet().forEach(id -> discovered.add(id.namespace()));
            namespaces.sync(discovered);
            this.loadTranslations(version, started, items, categories, List.copyOf(tops), Map.copyOf(parents), namespaces);
        } catch (Exception | LinkageError e) {
            this.fail(version, e);
        }
    }

    private Map<Key, Category> captureCategories() {
        Map<Key, Category> categories = new LinkedHashMap<>();
        var roots = CraftEngine.instance().itemBrowserManager().categories();
        ArrayDeque<Category> queue = new ArrayDeque<>();
        if (roots != null) queue.addAll(roots);
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

    private void loadTranslations(long version, long started, Map<Key, ItemDefinition> items,
                                  Map<Key, Category> categories, List<Category> tops, Map<Key, Key> parents,
                                  CategoryNamespaces namespaces) {
        // Resolve CraftEngine configuration here; the worker only receives a path and plain locale names.
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
                    this.captureNames(version, started, items, categories, tops, parents, namespaces, loaded.translations());
                });
            } catch (IllegalPluginAccessException ignored) {
                // Plugin shutdown raced with worker completion.
            }
        });
    }

    private Path resourcePackPath() {
        Path dataFolder = this.plugin.getDataFolder().toPath().toAbsolutePath();
        Path serverRoot = dataFolder.getParent().getParent();
        try {
            // Shared by both legacy pack generation and newer workflow-based CraftEngine versions.
            Path configured = Config.resourcePackPath();
            if (configured != null) return configured.isAbsolute() ? configured : serverRoot.resolve(configured).normalize();
        } catch (RuntimeException | LinkageError ignored) {
        }
        return dataFolder.resolveSibling("CraftEngine").resolve("generated/resource_pack.zip");
    }

    private void captureNames(long version, long started, Map<Key, ItemDefinition> items,
                              Map<Key, Category> categories, List<Category> tops, Map<Key, Key> parents,
                              CategoryNamespaces namespaces, Map<String, String> translations) {
        Iterator<Map.Entry<Key, ItemDefinition>> itemIterator = items.entrySet().iterator();
        Iterator<Map.Entry<Key, Category>> categoryIterator = categories.entrySet().iterator();
        Map<Key, String> itemNames = new LinkedHashMap<>();
        Map<Key, String> translationKeys = new LinkedHashMap<>();
        Map<Key, String> categoryNames = new LinkedHashMap<>();
        Runnable batch = new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(version)) return;
                try {
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
                // Hand off plain text only: no CraftEngine managers or ItemStacks on this worker.
                indexTask = plugin.tasks().async(() -> {
                    try {
                        SearchIndex search = SearchIndex.fromNames(itemNames, translationKeys);
                        CategoryIndex categorySearch = CategoryIndex.fromNames(categoryNames);
                        if (!isCurrent(version)) return;
                        plugin.tasks().global(() -> {
                            if (!isCurrent(version)) return;
                            snapshot = new Snapshot(items, categories, tops, parents, search, categorySearch, namespaces);
                            ready = true;
                            plugin.getLogger().info(plugin.lang().text("logs.data-ready", "items", String.valueOf(items.size()),
                                    "categories", String.valueOf(tops.size()), "elapsed",
                                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1_000_000.0)));
                            finish(true);
                        });
                    } catch (IllegalPluginAccessException ignored) {
                        // Plugin shutdown raced with worker completion.
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
        return state == null ? this.emptyNamespaces : state.namespaces();
    }
    public long generation() { return this.generation; }
    public boolean isReady() { return this.ready; }
    public Map<Key, ItemDefinition> items() {
        Snapshot state = this.snapshot;
        return state == null ? Map.of() : state.items();
    }
    public Optional<ItemDefinition> item(Key id) { return Optional.ofNullable(this.items().get(id)); }
    public Optional<Category> category(Key id) { return Optional.ofNullable(this.categoriesById().get(id)); }
    public List<Category> topCategories() {
        Snapshot state = this.snapshot;
        return state == null ? List.of() : state.tops();
    }
    public Map<Key, Category> categoriesById() {
        Snapshot state = this.snapshot;
        return state == null ? Map.of() : state.categories();
    }
    public Key parentOf(Key id) {
        Snapshot state = this.snapshot;
        return state == null ? null : state.parents().get(id);
    }
    public SearchIndex searchIndex() {
        Snapshot state = this.snapshot;
        return state == null ? null : state.search();
    }
    public CategoryIndex categoryIndex() {
        Snapshot state = this.snapshot;
        return state == null ? null : state.categorySearch();
    }

    private record Snapshot(Map<Key, ItemDefinition> items, Map<Key, Category> categories, List<Category> tops,
                            Map<Key, Key> parents, SearchIndex search, CategoryIndex categorySearch,
                            CategoryNamespaces namespaces) {}
}

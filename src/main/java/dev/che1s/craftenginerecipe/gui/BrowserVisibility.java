package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.WorkstationRecipes;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import dev.che1s.craftenginerecipe.recipe.access.HeldRecipeScope;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单次玩家操作内共享的配方与物品可见性缓存。 */
public final class BrowserVisibility implements RecipeAccessPolicy {
    private final CraftEngineRecipePlugin plugin;
    private final CraftEngineData data;
    private final BrowserService browser;
    private final Player player;
    private final ExternalRecipeSnapshot snapshot;
    private final Map<Key, Boolean> queryable = new HashMap<>();
    private final Map<RecipeEntry, Boolean> allowed = new IdentityHashMap<>();
    private Set<Key> applied;
    private Set<Key> workstationApplied;
    private Set<Key> additional;

    BrowserVisibility(CraftEngineRecipePlugin plugin, CraftEngineData data, BrowserService browser, Player player) {
        this.plugin = plugin;
        this.data = data;
        this.browser = browser;
        this.player = player;
        this.snapshot = plugin.externalRecipes().snapshot();
    }

    private Set<Key> applied() {
        if (this.applied == null) {
            try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.VISIBLE_ITEMS)) {
                this.applied = HeldRecipeScope.visibleItems(this.browser.allTopCategories(),
                        this.data.categoriesById(), this.data.categoryGraph(), this.data.items().keySet(),
                        this.data.namespaces(), this.player);
            }
        }
        return this.applied;
    }

    private Set<Key> workstationApplied() {
        if (this.workstationApplied == null) {
            try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.VISIBLE_ITEMS)) {
                this.workstationApplied = HeldRecipeScope.visibleItemsForWorkstations(
                        this.browser.allTopCategories(), this.data.categoriesById(), this.data.categoryGraph(),
                        this.data.items().keySet(), this.data.namespaces(), this.player);
            }
        }
        return this.workstationApplied;
    }

    @Override
    public boolean canView(RecipeEntry entry) {
        Boolean cached = this.allowed.get(entry);
        if (cached != null) return cached;
        boolean visible = this.test(entry);
        this.allowed.put(entry, visible);
        return visible;
    }

    private boolean test(RecipeEntry entry) {
        try {
            if (!entry.canView(this.player)) return false;
            if (entry.display() == null) {
                Key result = WorkstationRecipes.resultKey(entry.nativeRecipe());
                return result == null || this.nativeItemVisible(result, this.applied());
            }
            if (BrowserService.isStandaloneMinecraftRecipe(entry.display().id())) {
                return entry.canView(this.player);
            }
            return entry.display().results().stream().allMatch(result -> {
                Key key = ExternalRecipeSnapshot.key(result.item());
                if ("cer_external".equals(key.namespace())) return true;
                if (this.data.items().containsKey(key)
                        || this.data.categoryGraph().hasItemReference(key)
                        || "minecraft".equals(key.namespace())) {
                    return this.nativeItemVisible(key, this.applied());
                }
                return this.data.namespaces().canView(this.player, key.namespace());
            });
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("RecipeProvider.visibility:" + entry.source(), error);
            return false;
        }
    }

    @Override
    public boolean canViewWorkstation(RecipeEntry entry) {
        if (entry.display() != null) return this.canView(entry);
        try {
            if (!entry.canView(this.player)) return false;
            Key result = WorkstationRecipes.resultKey(entry.nativeRecipe());
            if (result == null) return true;
            boolean minecraftNamespaceVisible = this.data.namespaces().entries().stream()
                    .anyMatch(candidate -> candidate instanceof CategoryNamespaces.NamespaceEntry namespace
                            && namespace.id().equals("minecraft"))
                    && this.data.namespaces().canView(this.player, "minecraft");
            return nativeItemVisible(result, this.workstationApplied().contains(result),
                    this.data.items().containsKey(result),
                    this.data.categoryGraph().hasItemReference(result),
                    this.data.namespaces().findNamespace("minecraft") != null,
                    minecraftNamespaceVisible);
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("RecipeProvider.workstationVisibility:" + entry.source(), error);
            return false;
        }
    }

    @Override
    public boolean canQuery(Key id) {
        return this.queryable.computeIfAbsent(id, key -> {
            if (this.nativeItemVisible(key, this.applied())) return true;
            return this.snapshot.results().getOrDefault(key, List.of()).stream().anyMatch(this::canView)
                    || this.snapshot.usages().getOrDefault(key, List.of()).stream().anyMatch(this::canView);
        });
    }

    boolean hasAdditional() {
        return this.snapshot.entries().stream().anyMatch(this::canView);
    }

    Set<Key> additionalItems() {
        if (this.additional != null) return this.additional;
        Set<Key> visible = new LinkedHashSet<>();
        for (RecipeEntry entry : this.snapshot.entries()) {
            if (!this.canView(entry)) continue;
            for (var result : entry.display().results()) {
                visible.add(ExternalRecipeSnapshot.key(result.item()));
            }
            for (var ingredient : entry.display().ingredients()) {
                if (ingredient.role() == RecipeIngredient.Role.INFO) continue;
                for (var item : ingredient.choices()) {
                    Key key = ExternalRecipeSnapshot.key(item);
                    if (this.canQuery(key)) visible.add(key);
                }
            }
        }
        return this.additional = java.util.Collections.unmodifiableSet(visible);
    }

    private boolean nativeItemVisible(Key id, Set<Key> visible) {
        boolean minecraftNamespaceVisible = this.data.namespaces().entries().stream()
                .anyMatch(entry -> entry instanceof CategoryNamespaces.NamespaceEntry namespace
                        && namespace.id().equals("minecraft"))
                && this.data.namespaces().canView(this.player, "minecraft");
        return nativeItemVisible(id, visible.contains(id), this.data.items().containsKey(id),
                this.data.categoryGraph().hasItemReference(id),
                this.data.namespaces().findNamespace("minecraft") != null, minecraftNamespaceVisible);
    }

    static boolean nativeItemVisible(Key id, boolean catalogVisible, boolean registered,
                                     boolean referenced, boolean namespaceConfigured,
                                     boolean namespaceVisible) {
        if (catalogVisible) return true;
        if (!"minecraft".equals(id.namespace())) return false;
        if (referenced) return false;
        if (registered) return true;
        if (!namespaceConfigured) return true;
        return namespaceVisible;
    }
}

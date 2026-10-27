package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import org.bukkit.entity.Player;

public final class RecipeIndex {

    private final CraftEngineRecipePlugin plugin;

    public RecipeIndex(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public List<Recipe> recipesForResult(Key result) {
        return this.plugin.data().recipeSnapshot().forResult(result);
    }

    public List<Recipe> recipesUsingIngredient(Key ingredient) {
        return this.plugin.data().recipeSnapshot().usingIngredient(ingredient);
    }

    public List<Recipe> recipesByType(RecipeType type) {
        return this.plugin.data().recipeSnapshot().byType(type);
    }

    public long generation() {
        // 高 32 位来自 CE 数据（物品/分类），低 32 位来自配方文件监视。
        // 两个来源独立刷新，组合值保证任一侧变化都会让缓存的点击动作失效。
        return (this.plugin.data().generation() << 32) ^ this.plugin.externalRecipes().generation();
    }

    public List<RecipeEntry> entries(Key item, boolean usage, Player player) {
        return this.entries(item, usage, this.plugin.browser(player).visibility(player));
    }

    public List<RecipeEntry> entries(Key item, boolean usage, RecipeAccessPolicy visibility) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.RECIPE_QUERY)) {
        var snapshot = this.plugin.externalRecipes().snapshot();
        return (usage ? snapshot.usages() : snapshot.results()).getOrDefault(item, List.of())
                .stream().filter(visibility::canView).toList();
        }
    }

    public List<RecipeEntry.Output> outputs(String station, Player player) {
        Map<Key, List<RecipeEntry>> grouped = new LinkedHashMap<>();
        var visibility = this.plugin.browser(player).visibility(player);
        java.util.function.Predicate<RecipeEntry> policy = visibility::canViewWorkstation;
        for (RecipeEntry entry : this.plugin.externalRecipes().snapshot().stations().getOrDefault(station, List.of())) {
            if (!policy.test(entry)) continue;
            var seen = new java.util.HashSet<Key>();
            for (var result : entry.display().results()) {
                Key key = ExternalRecipeSnapshot.key(result.item());
                if (seen.add(key)) {
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
                }
            }
        }
        return grouped.entrySet().stream().map(entry -> new RecipeEntry.Output(entry.getKey(), entry.getValue())).toList();
    }

    public List<RecipeWorkstation> workstations(Player player) {
        Map<String, RecipeWorkstation> available = this.availableWorkstations();
        this.plugin.workstationConfig().sync(available.keySet());
        Set<String> nativeIds = new java.util.LinkedHashSet<>();
        Map<String, RecipeWorkstation> stations = new LinkedHashMap<>();
        String[] icons = {"crafting_table", "furnace", "blast_furnace", "smoker", "campfire", "stonecutter", "smithing_table", "brewing_stand"};
        RecipeType[] types = {RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING,
                RecipeType.CAMPFIRE_COOKING, RecipeType.STONECUTTING, RecipeType.SMITHING, RecipeType.BREWING};
        for (int i = 0; i < types.length; i++) {
            String id = stationId(types[i]);
            nativeIds.add(id);
            stations.put(id, new RecipeWorkstation(id, RecipeItem.of("minecraft:" + icons[i]),
                    this.plugin.lang().text("ui.workstations.categories." + types[i].name().toLowerCase(Locale.ROOT))));
        }
        var snapshot = this.plugin.externalRecipes().snapshot();
        var policy = this.plugin.browser(player).recipeVisibility(player);
        available.forEach((id, definition) -> {
            if (nativeIds.contains(id) || snapshot.stations().getOrDefault(id, List.of()).stream().anyMatch(policy)) {
                stations.putIfAbsent(id, definition);
            }
        });
        return this.plugin.workstationConfig().sort(stations.values());
    }

    /** 在配方快照刷新后同步 workstations.yml，不依赖某个玩家是否已打开页面。 */
    public int syncWorkstations() {
        return this.plugin.workstationConfig().sync(this.availableWorkstations().keySet());
    }

    private Map<String, RecipeWorkstation> availableWorkstations() {
        Map<String, RecipeWorkstation> stations = new LinkedHashMap<>();
        String[] icons = {"crafting_table", "furnace", "blast_furnace", "smoker", "campfire", "stonecutter", "smithing_table", "brewing_stand"};
        RecipeType[] types = {RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING,
                RecipeType.CAMPFIRE_COOKING, RecipeType.STONECUTTING, RecipeType.SMITHING, RecipeType.BREWING};
        for (int i = 0; i < types.length; i++) {
            String id = stationId(types[i]);
            stations.put(id, new RecipeWorkstation(id, RecipeItem.of("minecraft:" + icons[i]),
                    this.plugin.lang().text("ui.workstations.categories." + types[i].name().toLowerCase(Locale.ROOT))));
        }
        this.plugin.externalRecipes().snapshot().workstations().forEach(stations::putIfAbsent);
        return stations;
    }

    public static String stationId(RecipeType type) { return "minecraft:" + type.name().toLowerCase(Locale.ROOT); }
    public static RecipeType nativeType(String station) {
        if (station == null || !station.startsWith("minecraft:")) return null;
        try { return RecipeType.valueOf(station.substring(10).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public static RecipeUiKind uiKindOf(Recipe recipe) {
        RecipeType type = recipe.type();
        if (type == null) {
            return RecipeUiKind.NONE;
        }
        return switch (type) {
            case CRAFTING -> RecipeUiKind.CRAFTING;
            case SMELTING -> RecipeUiKind.SMELTING;
            case BLASTING -> RecipeUiKind.BLASTING;
            case SMOKING -> RecipeUiKind.SMOKING;
            case CAMPFIRE_COOKING -> RecipeUiKind.CAMPFIRE;
            case STONECUTTING -> RecipeUiKind.STONECUTTING;
            case SMITHING -> RecipeUiKind.SMITHING;
            case BREWING -> RecipeUiKind.BREWING;
        };
    }

    public enum RecipeUiKind {
        CRAFTING,
        SMELTING,
        BLASTING,
        SMOKING,
        CAMPFIRE,
        STONECUTTING,
        SMITHING,
        BREWING,
        NONE
    }
}

package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 过滤合成器快照，不改变配方顺序或产物可见性。 */
public final class WorkstationRecipes {
    private WorkstationRecipes() {
    }

    /** 每条配方对应一个条目，即使多条配方产出同一物品。 */
    public record Entry(Recipe recipe, Key result) {
    }

    /** 一个产物图标，仅包含指定合成器中的该产物配方。 */
    public record Output(Key result, List<Recipe> recipes) {
        public Output {
            recipes = List.copyOf(recipes);
        }
    }

    /**
     * 仅使用声明的固定产物。锻造纹饰等动态配方在没有输入时无法确定可见产物，
     * 因此返回 {@code null}。
     */
    public static Key resultKey(Recipe recipe) {
        if (recipe instanceof FixedResultRecipe fixed) {
            try {
                CustomRecipeResult result = fixed.result();
                return result != null && result.item() != null ? result.item().id() : null;
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("WorkstationRecipes.resultKey", error);
            }
        }
        return null;
    }

    /**
     * 调用方提供某一配方类型的快照列表和玩家可见物品键。
     * 材料可见性绝不会赋予配方产物可见性。
     */
    public static List<Entry> entries(List<Recipe> recipes, Set<Key> visibleResults) {
        if (recipes == null || recipes.isEmpty() || visibleResults == null || visibleResults.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (Recipe recipe : recipes) {
            Key result = resultKey(recipe);
            if (result != null && visibleResults.contains(result)) {
                entries.add(new Entry(recipe, result));
            }
        }
        return List.copyOf(entries);
    }

    /** 按配方级可见性过滤；不要求产物必须属于物品目录。 */
    public static List<Entry> entries(List<Recipe> recipes, Predicate<Recipe> visibleRecipe) {
        if (recipes == null || recipes.isEmpty() || visibleRecipe == null) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (Recipe recipe : recipes) {
            Key result = resultKey(recipe);
            if (result != null && visibleRecipe.test(recipe)) entries.add(new Entry(recipe, result));
        }
        return List.copyOf(entries);
    }

    public static List<Recipe> visible(List<Recipe> recipes, Set<Key> visibleResults) {
        return entries(recipes, visibleResults).stream().map(Entry::recipe).toList();
    }

    /** 按首次出现顺序分组产物，同时保留每个产物的配方顺序。 */
    public static List<Output> outputs(List<Recipe> recipes, Set<Key> visibleResults) {
        Map<Key, List<Recipe>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries(recipes, visibleResults)) {
            grouped.computeIfAbsent(entry.result(), ignored -> new ArrayList<>()).add(entry.recipe());
        }
        List<Output> outputs = new ArrayList<>(grouped.size());
        grouped.forEach((result, matches) -> outputs.add(new Output(result, matches)));
        return List.copyOf(outputs);
    }
}

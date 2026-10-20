package dev.che1s.craftenginerecipe.data;

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

/** Filters a workstation's snapshot without changing recipe order or result visibility. */
public final class WorkstationRecipes {
    private WorkstationRecipes() {
    }

    /** One entry per recipe, even when several recipes produce the same item. */
    public record Entry(Recipe recipe, Key result) {
    }

    /** A single output icon with only its recipes from the supplied workstation. */
    public record Output(Key result, List<Recipe> recipes) {
        public Output {
            recipes = List.copyOf(recipes);
        }
    }

    /**
     * Uses only declared fixed results. Dynamic recipes, including smithing trims, cannot
     * establish a visible output without an input and therefore return {@code null}.
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
     * The caller supplies the snapshot list for one recipe type and the player's visible
     * item keys. Ingredients never grant visibility to a recipe's result.
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

    public static List<Recipe> visible(List<Recipe> recipes, Set<Key> visibleResults) {
        return entries(recipes, visibleResults).stream().map(Entry::recipe).toList();
    }

    /** Groups outputs in first-appearance order while retaining each output's recipe order. */
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

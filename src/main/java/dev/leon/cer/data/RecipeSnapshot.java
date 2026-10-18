package dev.leon.cer.data;

import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeManager;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copies CE's query lists, including their order, for one published data generation. */
public final class RecipeSnapshot {
    public static final RecipeSnapshot EMPTY = new RecipeSnapshot(Map.of(), Map.of(), Map.of());

    private final Map<Key, List<Recipe>> results;
    private final Map<Key, List<Recipe>> ingredients;
    private final Map<RecipeType, List<Recipe>> types;

    private RecipeSnapshot(Map<Key, List<Recipe>> results, Map<Key, List<Recipe>> ingredients,
                           Map<RecipeType, List<Recipe>> types) {
        this.results = Map.copyOf(results);
        this.ingredients = Map.copyOf(ingredients);
        this.types = Map.copyOf(types);
    }

    public static RecipeSnapshot capture(RecipeManager manager, Collection<Key> knownItems) {
        Map<RecipeType, List<Recipe>> types = new EnumMap<>(RecipeType.class);
        Set<Key> keys = new HashSet<>(knownItems);
        for (RecipeType type : RecipeType.values()) {
            List<Recipe> recipes = copy(manager.recipesByType(type));
            types.put(type, recipes);
            for (Recipe recipe : recipes) {
                if (recipe instanceof FixedResultRecipe fixed && fixed.result() != null
                        && fixed.result().item() != null) keys.add(fixed.result().item().id());
                for (var ingredient : recipe.ingredientsInUse()) {
                    // Include vanilla fallback keys used by the UI too, but never reconstruct CE's
                    // membership rules: only its own query lists determine the final results.
                    ingredient.items().forEach(item -> keys.add(item.key()));
                    ingredient.minecraftItems().forEach(item -> keys.add(item.key()));
                }
            }
        }
        Map<Key, List<Recipe>> results = new HashMap<>();
        Map<Key, List<Recipe>> ingredients = new HashMap<>();
        for (Key key : keys) {
            List<Recipe> result = copy(manager.recipeByResult(key));
            List<Recipe> usage = copy(manager.recipeByIngredient(key));
            if (!result.isEmpty()) results.put(key, result);
            if (!usage.isEmpty()) ingredients.put(key, usage);
        }
        return new RecipeSnapshot(results, ingredients, types);
    }

    private static List<Recipe> copy(List<Recipe> recipes) {
        return recipes == null ? List.of() : List.copyOf(recipes);
    }

    public List<Recipe> forResult(Key key) { return this.results.getOrDefault(key, List.of()); }
    public List<Recipe> usingIngredient(Key key) { return this.ingredients.getOrDefault(key, List.of()); }
    public List<Recipe> byType(RecipeType type) { return this.types.getOrDefault(type, List.of()); }
}

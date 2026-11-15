package dev.che1s.craftenginerecipe.recipe.query;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftableRecipeServiceTest {
    private static final RecipeItem PLANKS = RecipeItem.of("minecraft:oak_planks");
    private static final RecipeItem BIRCH = RecipeItem.of("minecraft:birch_planks");
    private static final RecipeItem STICK = RecipeItem.of("minecraft:stick");

    @Test
    void findsOutputsWhoseConsumedIngredientsFitTheInventory() {
        DisplayRecipe recipe = recipe("test:sticks", List.of(
                ingredient(10, 2, PLANKS)), List.of(result(STICK)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(
                new RecipeEntry("custom", null, recipe, () -> true)));

        assertEquals(List.of(Key.of("minecraft:stick")),
                CraftableRecipeService.find(snapshot,
                        Map.of(Key.of("minecraft:oak_planks"), 2), entry -> true));
        assertEquals(List.of(), CraftableRecipeService.find(snapshot,
                Map.of(Key.of("minecraft:oak_planks"), 1), entry -> true));
    }

    @Test
    void backtrackingChoosesAnAlternativeThatLeavesRequiredItemsForLaterSlots() {
        DisplayRecipe recipe = recipe("test:backtracking", List.of(
                ingredient(10, 1, PLANKS, BIRCH),
                ingredient(11, 1, BIRCH)), List.of(result(STICK)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(
                new RecipeEntry("custom", null, recipe, () -> true)));

        assertTrue(CraftableRecipeService.find(snapshot, Map.of(
                        Key.of("minecraft:oak_planks"), 1,
                        Key.of("minecraft:birch_planks"), 1), entry -> true)
                .contains(Key.of("minecraft:stick")));
    }

    private static DisplayRecipe recipe(String id, List<RecipeIngredient> ingredients, List<RecipeResult> results) {
        return new DisplayRecipe(id, new RecipeWorkstation("minecraft:crafting", PLANKS, "Workbench"),
                RecipeLayout.defaults(), ingredients, results, List.of(), null, player -> true);
    }

    private static RecipeIngredient ingredient(int slot, int amount, RecipeItem... choices) {
        return new RecipeIngredient(List.of(choices), amount, slot, RecipeIngredient.Role.INGREDIENT);
    }

    private static RecipeResult result(RecipeItem item) {
        return new RecipeResult(item, 1, 24, null);
    }

}

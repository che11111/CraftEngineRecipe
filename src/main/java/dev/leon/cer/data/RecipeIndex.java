package dev.leon.cer.data;

import dev.leon.cer.CraftEngineRecipePlugin;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;

import java.util.List;

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

package dev.leon.cer.data;

import dev.leon.cer.CraftEngineRecipePlugin;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;

import java.util.List;

public final class RecipeIndex {

    private final CraftEngineRecipePlugin plugin;

    public RecipeIndex(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public List<Recipe> recipesForResult(Key result) {
        try {
            List<Recipe> list = CraftEngine.instance().recipeManager().recipeByResult(result);
            return list == null ? List.of() : list;
        } catch (Throwable t) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.result-query-failed", "id", result.toString(), "error", t.getMessage()));
            return List.of();
        }
    }

    public List<Recipe> recipesUsingIngredient(Key ingredient) {
        try {
            List<Recipe> list = CraftEngine.instance().recipeManager().recipeByIngredient(ingredient);
            return list == null ? List.of() : list;
        } catch (Throwable t) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.usage-query-failed", "id", ingredient.toString(), "error", t.getMessage()));
            return List.of();
        }
    }

    public List<Recipe> recipesByType(RecipeType type) {
        try {
            List<Recipe> list = CraftEngine.instance().recipeManager().recipesByType(type);
            return list == null ? List.of() : list;
        } catch (Throwable t) {
            return List.of();
        }
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

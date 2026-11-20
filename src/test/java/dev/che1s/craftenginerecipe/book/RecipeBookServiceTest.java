package dev.che1s.craftenginerecipe.book;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import dev.che1s.craftenginerecipe.recipe.io.CustomRecipeLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeBookServiceTest {
    @Test
    void automaticNonSneakActionsFollowOffhandState() {
        assertEquals(PlayerPreferences.BookAction.ITEM_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT, true));
        assertEquals(PlayerPreferences.BookAction.OFFHAND_RECIPE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT, false));
        assertEquals(PlayerPreferences.BookAction.TABLE_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_RIGHT, true));
        assertEquals(PlayerPreferences.BookAction.OFFHAND_USAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_RIGHT, false));
    }

    @Test
    void explicitBindingsRemainUnchanged() {
        assertEquals(PlayerPreferences.BookAction.SEARCH_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.SEARCH_PAGE, true));
        assertEquals(PlayerPreferences.BookAction.PERSONALIZATION,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.PERSONALIZATION, false));
    }

    @Test
    void generatedQueryRecipeContainsTheConfiguredBookRecipe(@TempDir Path dataFolder) throws Exception {
        Path recipeFile = dataFolder.resolve("recipes/cer_recipe_book/recipe_book.yml");
        Files.createDirectories(recipeFile.getParent());
        Files.writeString(recipeFile, RecipeBookService.queryRecipeDocument(), StandardCharsets.UTF_8);

        DisplayRecipe recipe = CustomRecipeLoader.allSources(dataFolder).load().getFirst();

        assertEquals(RecipeBookService.QUERY_RECIPE_ID, recipe.id());
        assertEquals("crafting", recipe.layout().page());
        assertEquals(RecipeBookService.ITEM_ID.toString(), recipe.results().getFirst().item().id());
        assertEquals(24, recipe.results().getFirst().slot());
        assertEquals(9, recipe.ingredients().size());
        assertEquals(java.util.List.of(
                        "minecraft:gold_nugget",
                        "minecraft:crafting_table",
                        "minecraft:gold_nugget",
                        "minecraft:paper",
                        "minecraft:book",
                        "minecraft:paper",
                        "minecraft:gold_nugget",
                        "minecraft:compass",
                        "minecraft:gold_nugget"),
                recipe.ingredients().stream()
                        .map(ingredient -> ingredient.choices().getFirst().id())
                        .toList());
    }
}

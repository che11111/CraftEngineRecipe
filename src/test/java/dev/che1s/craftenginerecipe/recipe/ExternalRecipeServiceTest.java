package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalRecipeServiceTest {
    @TempDir
    Path directory;

    @Test
    void emptySourceDirectoriesAreNotTreatedAsCompletedExports() throws Exception {
        assertFalse(ExternalRecipeService.hasExportedRecipeFiles(this.directory));

        Files.createDirectories(this.directory.resolve("nested"));
        Files.writeString(this.directory.resolve("nested/other.json"), "{}");
        assertFalse(ExternalRecipeService.hasExportedRecipeFiles(this.directory));

        Files.writeString(this.directory.resolve("nested/crafting.yml"), "Recipes: {}");
        assertTrue(ExternalRecipeService.hasExportedRecipeFiles(this.directory));
    }

    @Test
    void sourceStatisticsCountDistinctWorkstations() {
        assertEquals(2, ExternalRecipeService.workstationCount(List.of(
                recipe("test:one", "farmersdelight:cooking_pot"),
                recipe("test:two", "farmersdelight:cooking_pot"),
                recipe("test:three", "farmersdelight:cutting_board"))));
    }

    @Test
    void sourceDirectoriesAreDiscoverableForThirdPartyProviders() {
        assertEquals(Map.of("private-output", "private_alchemy"),
                ExternalRecipeService.sourceDirectoryIndex(List.of(
                        provider("private_alchemy", "private-output"))));

        assertThrows(IllegalArgumentException.class, () ->
                ExternalRecipeService.sourceDirectoryIndex(List.of(
                        provider("first", "shared"),
                        provider("second", "shared"))));
        assertThrows(IllegalArgumentException.class, () ->
                ExternalRecipeService.sourceDirectoryIndex(List.of(
                        provider("unsafe", "../outside"))));
    }

    private static DisplayRecipe recipe(String id, String workstation) {
        return new DisplayRecipe(id,
                new RecipeWorkstation(workstation, RecipeItem.of("minecraft:cauldron"), null),
                RecipeLayout.defaults(), List.of(), List.of(), List.of(), null, null);
    }

    private static RecipeSourceProvider provider(String id, String outputDirectory) {
        return new RecipeSourceProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String outputDirectory() {
                return outputDirectory;
            }

            @Override
            public RecipeSource create(SourceContext context) {
                return null;
            }
        };
    }
}

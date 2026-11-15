package dev.che1s.craftenginerecipe.recipe.io;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeFileExporterTest {
    @TempDir
    Path directory;

    @Test
    void replacingAnExportSwapsTheCompleteDirectory() throws Exception {
        Path recipes = this.directory.resolve("recipes");
        RecipeFileExporter.Result first = RecipeFileExporter.export(
                recipes, "private", List.of(recipe("private:old")));
        assertTrue(first.installed());

        RecipeFileExporter.Result second = RecipeFileExporter.exportReplacing(
                recipes, "private", List.of(recipe("private:new")));

        assertTrue(second.installed());
        String contents;
        try (var paths = Files.walk(recipes.resolve("private"))) {
            Path file = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
            contents = Files.readString(file);
        }
        assertTrue(contents.contains("private:new"));
        assertFalse(contents.contains("private:old"));
    }

    private static DisplayRecipe recipe(String id) {
        return new DisplayRecipe(id,
                new RecipeWorkstation("private:station",
                        RecipeItem.of("minecraft:crafting_table"), "Private"),
                RecipeLayout.defaults(), List.of(),
                List.of(new RecipeResult(RecipeItem.of("minecraft:stone"), 1, 16, null)),
                List.of(), null, null);
    }
}

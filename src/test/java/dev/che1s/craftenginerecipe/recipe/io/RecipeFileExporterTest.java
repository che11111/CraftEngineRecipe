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
import java.util.Map;

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

    @Test
    void localizedItemNamesAreWrittenAsYamlLanguageMaps() throws Exception {
        RecipeItem wheat = new RecipeItem("minecraft", "minecraft:wheat", null,
                "Wheat", List.of(), Map.of(
                "en_US", "Wheat",
                "zh_CN", "小麦",
                "ja_JP", "小麦"));
        DisplayRecipe recipe = new DisplayRecipe("cultivation:wheat",
                new RecipeWorkstation("minecraft:cultivation",
                        RecipeItem.of("minecraft:farmland"), "Cultivation"),
                RecipeLayout.defaults(), List.of(),
                List.of(new RecipeResult(wheat, 1, 16, null)),
                List.of(), null, null);

        RecipeFileExporter.export(this.directory.resolve("recipes"), "minecraft-cultivation",
                List.of(recipe));

        Path file;
        try (var paths = Files.walk(this.directory.resolve("recipes"))) {
            file = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        String contents = Files.readString(file);
        assertTrue(contents.contains("en_US: Wheat"));
        assertTrue(contents.contains("zh_CN: 小麦"));
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

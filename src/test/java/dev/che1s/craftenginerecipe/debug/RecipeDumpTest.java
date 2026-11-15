package dev.che1s.craftenginerecipe.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.*;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.item.BuildableItem;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.FunctionalRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.IngredientElement;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.item.recipe.input.RecipeInput;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.item.recipe.result.PostProcessor;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecipeDumpTest {
    @TempDir Path directory;

    @Test void externalSnapshotRetainsChoicesRolesCountsAndPermissionsWithoutInvokingPredicates() {
        var recipe = new DisplayRecipe("test:soup", new RecipeWorkstation("test:pot", RecipeItem.of("minecraft:cauldron"), "锅"),
                RecipeLayout.defaults(), List.of(new RecipeIngredient(List.of(RecipeItem.of("test:onion"), RecipeItem.of("minecraft:carrot")),
                2, 20, RecipeIngredient.Role.INGREDIENT), new RecipeIngredient(List.of(RecipeItem.of("test:knife")), 1, 21, RecipeIngredient.Role.TOOL)),
                List.of(new RecipeResult(RecipeItem.of("test:soup"), 3, 24, "Extra output")), List.of("hot"), "recipes.soup",
                player -> { throw new AssertionError("Export must not evaluate player visibility"); });
        JsonObject result = RecipeDump.external(new RecipeEntry("custom", null, recipe,
                () -> { throw new AssertionError("Export must not evaluate runtime predicates"); }));
        assertEquals("custom", result.get("source").getAsString());
        assertEquals("recipes.soup", result.get("permission").getAsString());
        assertEquals("锅", result.getAsJsonObject("workstation").get("name").getAsString());
        var ingredients = result.getAsJsonArray("ingredients");
        assertEquals(2, ingredients.size());
        assertEquals(2, ingredients.get(0).getAsJsonObject().getAsJsonArray("choices").size());
        assertEquals(2, ingredients.get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals("TOOL", ingredients.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals(3, result.getAsJsonArray("results").get(0).getAsJsonObject().get("amount").getAsInt());
    }

    @Test void craftEngineSnapshotIncludesIngredientRulesAndResultWithoutBuildingOrCraftingItems() {
        var ingredient = Ingredient.of(List.of(IngredientElement.item(Key.of("test:input")), IngredientElement.tag(Key.of("test:tag"))),
                Set.of(), Set.of(), true, 4, null);
        var result = RecipeDump.nativeRecipe(new NativeRecipe(ingredient));
        assertEquals("test:recipe", result.get("id").getAsString());
        assertEquals("CRAFTING", result.get("type").getAsString());
        assertEquals("test:output", result.getAsJsonObject("result").get("item").getAsString());
        assertEquals(7, result.getAsJsonObject("result").get("amount").getAsInt());
        var input = result.getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals(4, input.get("amount").getAsInt());
        assertEquals("#test:tag", input.getAsJsonArray("elements").get(1).getAsString());
    }

    @Test void jsonFilesAreCompleteUtf8UniqueAndLeaveNoTemporaryFiles() throws Exception {
        JsonObject document = new JsonObject();
        document.addProperty("text", "配方 <test> \"quoted\"\n");
        Path first = DebugService.write(this.directory.resolve("debug"), document);
        Path second = DebugService.write(this.directory.resolve("debug"), document);
        assertNotEquals(first, second);
        assertEquals(document, JsonParser.parseString(Files.readString(first)));
        assertEquals(document, JsonParser.parseString(Files.readString(second)));
        try (var paths = Files.list(first.getParent())) {
            assertEquals(2, paths.count());
        }
        assertTrue(first.getFileName().toString().matches("recipes-\\d{8}-\\d{6}-\\d{3}-[a-f0-9]{8}\\.json"));
    }

    @Test void craftEngineRecipesWithAbsentOptionalFunctionsAndPostProcessorsStillExport() {
        var ingredient = Ingredient.of(List.of(IngredientElement.item(Key.of("test:input"))), Set.of(), Set.of(), true, 1, null);
        var result = RecipeDump.nativeRecipe(new NativeRecipe(ingredient, null));
        assertEquals("test:output", result.getAsJsonObject("result").get("item").getAsString());
        assertEquals(7, result.getAsJsonObject("result").get("amount").getAsInt());
        assertTrue(result.getAsJsonObject("result").getAsJsonArray("postProcessorClasses").isEmpty());
        assertTrue(result.getAsJsonArray("functionClasses").isEmpty());
    }

    private record NativeRecipe(Ingredient ingredient, PostProcessor[] postProcessors) implements FixedResultRecipe, FunctionalRecipe {
        NativeRecipe(Ingredient ingredient) { this(ingredient, new PostProcessor[0]); }
        @Override public RecipeType type() { return RecipeType.CRAFTING; }
        @Override public Key id() { return Key.of("test:recipe"); }
        @Override public Key serializerType() { return Key.of("test:serializer"); }
        @Override public List<Ingredient> ingredientsInUse() { return List.of(this.ingredient); }
        @Override public CustomRecipeResult result() {
            return new CustomRecipeResult(new BuildableItem() {
                @Override public Key id() { return Key.of("test:output"); }
                @Override public Item buildItem(ItemBuildContext context, int count) { throw new AssertionError("Never build items"); }
            }, 7, this.postProcessors);
        }
        @Override public Function<Context>[] functions() { return null; }
        @Override public boolean matches(RecipeInput input) { throw new AssertionError("Never match recipes"); }
        @Override public void takeInput(RecipeInput input, int amount) { throw new AssertionError("Never consume items"); }
        @Override public Item assemble(RecipeInput input, ItemBuildContext context) { throw new AssertionError("Never assemble items"); }
    }
}

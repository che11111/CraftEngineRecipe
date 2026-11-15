package dev.che1s.craftenginerecipe.recipe;

import net.momirealms.craftengine.core.item.BuildableItem;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTransformRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTrimRecipe;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.item.recipe.input.RecipeInput;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.item.recipe.result.PostProcessor;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkstationRecipesTest {
    private static final Key VISIBLE = Key.of("test:visible");
    private static final Key HIDDEN = Key.of("test:hidden");

    @ParameterizedTest
    @EnumSource(RecipeType.class)
    void filtersEachWorkstationByResultWithoutInspectingIngredients(RecipeType type) {
        Recipe hidden = fixed(type, "hidden", HIDDEN);
        Recipe visible = fixed(type, "visible", VISIBLE);

        List<WorkstationRecipes.Entry> entries = WorkstationRecipes.entries(
                List.of(hidden, visible), Set.of(VISIBLE));

        assertEquals(1, entries.size());
        assertSame(visible, entries.getFirst().recipe());
        assertEquals(VISIBLE, entries.getFirst().result());
    }

    @Test
    void retainsOrderDuplicateReferencesAndSeparateRecipesForTheSameResult() {
        Recipe first = fixed(RecipeType.SMELTING, "first", VISIBLE);
        Recipe hidden = fixed(RecipeType.SMELTING, "hidden", HIDDEN);
        Recipe second = fixed(RecipeType.SMELTING, "second", VISIBLE);
        List<Recipe> snapshot = List.of(second, hidden, first, second);

        List<Recipe> visible = WorkstationRecipes.visible(snapshot, Set.of(VISIBLE));

        assertEquals(List.of(second, first, second), visible);
        assertSame(second, visible.getFirst());
        assertSame(second, visible.getLast());
    }

    @Test
    void usesOnlyTheSuppliedWorkstationSnapshot() {
        Recipe smelting = fixed(RecipeType.SMELTING, "smelting", VISIBLE);
        Recipe crafting = fixed(RecipeType.CRAFTING, "crafting", VISIBLE);

        assertEquals(List.of(smelting), WorkstationRecipes.visible(List.of(smelting), Set.of(VISIBLE)));
        assertEquals(List.of(crafting), WorkstationRecipes.visible(List.of(crafting), Set.of(VISIBLE)));
    }

    @Test
    void recipeLevelVisibilityDoesNotRequireTheOutputToBeInTheItemCatalog() {
        Key honeyBottle = Key.of("minecraft:honey_bottle");
        Recipe brewing = fixed(RecipeType.BREWING, "honeycomb_honey", honeyBottle);

        List<WorkstationRecipes.Entry> entries = WorkstationRecipes.entries(
                List.of(brewing), recipe -> true);

        assertEquals(1, entries.size());
        assertEquals(honeyBottle, entries.getFirst().result());
        assertSame(brewing, entries.getFirst().recipe());
        assertEquals(List.of(), WorkstationRecipes.entries(List.of(brewing), recipe -> false));
    }

    @Test
    void groupsTwoOutputsByFirstAppearanceAndRetainsTheirRecipeOrderAndDuplicates() {
        Key other = Key.of("test:other");
        Recipe first = fixed(RecipeType.SMELTING, "first", VISIBLE);
        Recipe second = fixed(RecipeType.SMELTING, "second", VISIBLE);
        Recipe otherRecipe = fixed(RecipeType.SMELTING, "other", other);
        Recipe hidden = fixed(RecipeType.SMELTING, "hidden", HIDDEN);

        List<WorkstationRecipes.Output> outputs = WorkstationRecipes.outputs(
                List.of(otherRecipe, first, hidden, second, first), Set.of(VISIBLE, other));

        assertEquals(List.of(other, VISIBLE), outputs.stream().map(WorkstationRecipes.Output::result).toList());
        assertEquals(List.of(otherRecipe), outputs.getFirst().recipes());
        assertEquals(List.of(first, second, first), outputs.getLast().recipes());
        assertSame(first, outputs.getLast().recipes().getLast());
        assertThrows(UnsupportedOperationException.class, outputs::clear);
        assertThrows(UnsupportedOperationException.class, () -> outputs.getLast().recipes().clear());
    }

    @Test
    void outputGroupsCopyTheirRecipeList() {
        Recipe recipe = fixed(RecipeType.CRAFTING, "visible", VISIBLE);
        List<Recipe> source = new ArrayList<>(List.of(recipe));
        WorkstationRecipes.Output output = new WorkstationRecipes.Output(VISIBLE, source);
        source.clear();
        assertEquals(List.of(recipe), output.recipes());
    }

    @Test
    void craftEngineSmithingTransformsDeclareFixedResultsButTrimsDoNot() {
        // 使用类字面量验证真实 CE 类型层级，无需启动其服务器序列化器。
        assertTrue(FixedResultRecipe.class.isAssignableFrom(CustomSmithingTransformRecipe.class));
        assertFalse(FixedResultRecipe.class.isAssignableFrom(CustomSmithingTrimRecipe.class));
    }

    @Test
    void listsTheUsersNetheriteKnifeSmithingResultOnlyWhenItsOutputIsVisible() {
        // 模拟服务器数据中 farmersdelight/configuration/knives.yml 的固定产物。
        Key knife = Key.of("farmersdelight:netherite_knife");
        Recipe recipe = fixed(RecipeType.SMITHING, "netherite_knife", knife);

        assertEquals(RecipeType.SMITHING, recipe.type());
        assertEquals(knife, WorkstationRecipes.resultKey(recipe));
        List<WorkstationRecipes.Output> outputs = WorkstationRecipes.outputs(List.of(recipe), Set.of(knife));
        assertEquals(1, outputs.size());
        assertEquals(knife, outputs.getFirst().result());
        assertSame(recipe, outputs.getFirst().recipes().getFirst());
        // 可见材料不能使隐藏产物获得可见性。
        assertEquals(List.of(), WorkstationRecipes.outputs(List.of(recipe), Set.of(
                Key.of("minecraft:netherite_upgrade_smithing_template"),
                Key.of("farmersdelight:diamond_knife"), Key.of("minecraft:netherite_ingot"))));
    }

    @Test
    void excludesDynamicMissingAndUnresolvableResults() {
        Recipe dynamic = new StubRecipe(RecipeType.SMITHING, "dynamic");
        Recipe noResult = new FixedStub(RecipeType.BREWING, "no_result", () -> null);
        Recipe noItem = new FixedStub(RecipeType.STONECUTTING, "no_item",
                () -> new CustomRecipeResult(null, 1, new PostProcessor[0]));
        Recipe noId = fixed(RecipeType.SMOKING, "no_id", null);

        for (Recipe recipe : List.of(dynamic, noResult, noItem, noId)) {
            assertNull(WorkstationRecipes.resultKey(recipe));
        }
        assertEquals(List.of(), WorkstationRecipes.entries(
                List.of(dynamic, noResult, noItem, noId), Set.of(VISIBLE)));
        assertNull(WorkstationRecipes.resultKey(null));
    }

    @Test
    void compatibilityFailuresHideTheResultButFatalErrorsPropagate() {
        Recipe incompatible = new FixedStub(RecipeType.SMELTING, "incompatible", () -> {
            throw new NoSuchMethodError("Changed CE API");
        });
        Recipe broken = new FixedStub(RecipeType.SMELTING, "broken", () -> {
            throw new IllegalStateException("Invalid result");
        });
        Recipe fatal = new FixedStub(RecipeType.SMELTING, "fatal", () -> {
            throw new OutOfMemoryError("Do not swallow fatal errors");
        });

        assertEquals(List.of(), WorkstationRecipes.visible(List.of(incompatible, broken), Set.of(VISIBLE)));
        assertThrows(OutOfMemoryError.class, () -> WorkstationRecipes.resultKey(fatal));
    }

    @Test
    void emptyVisibilityAndMissingSnapshotsExposeNothing() {
        Recipe recipe = fixed(RecipeType.CRAFTING, "visible", VISIBLE);
        assertEquals(List.of(), WorkstationRecipes.entries(null, Set.of(VISIBLE)));
        assertEquals(List.of(), WorkstationRecipes.entries(List.of(), Set.of(VISIBLE)));
        assertEquals(List.of(), WorkstationRecipes.entries(List.of(recipe), (Set<Key>) null));
        assertEquals(List.of(), WorkstationRecipes.entries(List.of(recipe), Set.of()));
    }

    @Test
    void returnedListsAreImmutableAndIndependentOfTheSource() {
        Recipe recipe = fixed(RecipeType.CRAFTING, "visible", VISIBLE);
        List<Recipe> source = new ArrayList<>(List.of(recipe));
        List<WorkstationRecipes.Entry> entries = WorkstationRecipes.entries(source, Set.of(VISIBLE));
        List<Recipe> visible = WorkstationRecipes.visible(source, Set.of(VISIBLE));
        source.clear();

        assertEquals(1, entries.size());
        assertEquals(List.of(recipe), visible);
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertThrows(UnsupportedOperationException.class, visible::clear);
    }

    private static Recipe fixed(RecipeType type, String id, Key result) {
        return new FixedStub(type, id, () -> result(result));
    }

    private static CustomRecipeResult result(Key result) {
        BuildableItem item = new BuildableItem() {
            @Override
            public Key id() {
                return result;
            }

            @Override
            public Item buildItem(ItemBuildContext context, int count) {
                throw new AssertionError("Filtering must not build items");
            }
        };
        return new CustomRecipeResult(item, 1, new PostProcessor[0]);
    }

    private static final class FixedStub extends StubRecipe implements FixedResultRecipe {
        private final Supplier<CustomRecipeResult> result;

        private FixedStub(RecipeType type, String id, Supplier<CustomRecipeResult> result) {
            super(type, id);
            this.result = result;
        }

        @Override
        public CustomRecipeResult result() {
            return this.result.get();
        }
    }

    private static class StubRecipe implements Recipe {
        private final RecipeType type;
        private final Key id;

        private StubRecipe(RecipeType type, String id) {
            this.type = type;
            this.id = Key.of("test:" + id);
        }

        @Override
        public RecipeType type() { return this.type; }

        @Override
        public Key id() { return this.id; }

        @Override
        public List<Ingredient> ingredientsInUse() {
            throw new AssertionError("Ingredients must never grant output visibility");
        }

        @Override
        public Key serializerType() { throw new AssertionError("Filtering must not inspect serializers"); }

        @Override
        public boolean matches(RecipeInput input) { throw new AssertionError("Filtering must not match input"); }

        @Override
        public void takeInput(RecipeInput input, int amount) {
            throw new AssertionError("Filtering must not consume input");
        }

        @Override
        public Item assemble(RecipeInput input, ItemBuildContext context) {
            throw new AssertionError("Filtering must not assemble recipes");
        }
    }
}

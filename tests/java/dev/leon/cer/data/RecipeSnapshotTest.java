package dev.leon.cer.data;

import net.momirealms.craftengine.core.item.BuildableItem;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeManager;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.item.recipe.input.RecipeInput;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.item.recipe.result.PostProcessor;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSnapshotTest {
    @Test
    void queryListsPreserveManagerOrderDuplicatesAndIdentityAfterItsMapsAndListsAreCleared() {
        MutableManager source = new MutableManager();
        Key key = Key.of("test:known");
        Recipe first = recipe("first"), second = recipe("second");
        List<Recipe> byType = new ArrayList<>(List.of(first, second, first));
        List<Recipe> byResult = new ArrayList<>(List.of(second, first, second));
        List<Recipe> byIngredient = new ArrayList<>(List.of(first, second));
        source.types.put(RecipeType.CRAFTING, byType);
        source.results.put(key, byResult);
        source.ingredients.put(key, byIngredient);
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.manager, List.of(key));
        byType.clear();
        byResult.clear();
        byIngredient.clear();
        source.types.clear();
        source.results.clear();
        source.ingredients.clear();
        source.rejectCalls = true;
        assertEquals(List.of(first, second, first), snapshot.byType(RecipeType.CRAFTING));
        assertEquals(List.of(second, first, second), snapshot.forResult(key));
        assertEquals(List.of(first, second), snapshot.usingIngredient(key));
        assertSame(second, snapshot.forResult(key).getFirst());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.byType(RecipeType.CRAFTING).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.forResult(key).add(first));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.usingIngredient(key).removeFirst());
    }

    @Test
    void resultAndIngredientMembershipComesFromManagerRatherThanRecipeIntrospection() {
        MutableManager source = new MutableManager();
        Key result = Key.of("test:declared_result"), ingredient = Key.of("test:declared_input");
        Recipe advertised = fixed("advertised", result, ingredient(ingredient, null));
        Recipe managerOnly = recipe("manager_only");
        source.types.put(RecipeType.CRAFTING, List.of(advertised));
        // CE may exclude advertised recipes and provide query-only entries. CER must copy that
        // decision instead of rebuilding membership from the declarations above.
        source.results.put(result, List.of(managerOnly));
        source.ingredients.put(ingredient, List.of());
        source.results.put(ingredient, List.of(managerOnly, advertised));
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.manager, List.of());
        assertEquals(List.of(managerOnly), snapshot.forResult(result));
        assertTrue(snapshot.usingIngredient(ingredient).isEmpty());
        assertEquals(List.of(managerOnly, advertised), snapshot.forResult(ingredient));
        assertEquals(List.of(advertised), snapshot.byType(RecipeType.CRAFTING));
    }

    @Test
    void declaredResultDoesNotInventARecipeWhenManagerResultLookupIsEmpty() {
        MutableManager source = new MutableManager();
        Key result = Key.of("test:excluded");
        Recipe advertised = fixed("excluded", result);
        source.types.put(RecipeType.CRAFTING, List.of(advertised));
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.manager, List.of());
        assertTrue(source.resultQueries.contains(result));
        assertTrue(snapshot.forResult(result).isEmpty());
        assertEquals(List.of(advertised), snapshot.byType(RecipeType.CRAFTING));
    }

    @Test
    void followsQueryKeysAcrossCustomResultsAndVanillaFallbackIngredientsWithoutKnownItems() {
        MutableManager source = new MutableManager();
        Key device = Key.of("test:device"), part = Key.of("test:part");
        Key ingot = Key.of("minecraft:iron_ingot"), ore = Key.of("minecraft:raw_iron");
        Recipe assemble = fixed("assemble", device, ingredient(part, null));
        Recipe makePart = fixed("part", part, ingredient(null, ingot));
        Recipe smelt = fixed("smelt", ingot, ingredient(null, ore));
        source.types.put(RecipeType.CRAFTING, List.of(assemble, makePart));
        source.types.put(RecipeType.SMELTING, List.of(smelt));
        source.results.put(device, List.of(assemble));
        source.results.put(part, List.of(makePart));
        source.results.put(ingot, List.of(smelt));
        source.ingredients.put(part, List.of(assemble));
        source.ingredients.put(ingot, List.of(makePart));
        source.ingredients.put(ore, List.of(smelt));
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.manager, List.of());
        assertEquals(Set.of(device, part, ingot, ore), source.resultQueries);
        assertEquals(Set.of(device, part, ingot, ore), source.ingredientQueries);
        assertEquals(List.of(assemble), snapshot.forResult(device));
        assertEquals(List.of(makePart), snapshot.forResult(part));
        assertEquals(List.of(smelt), snapshot.forResult(ingot));
        assertEquals(List.of(assemble), snapshot.usingIngredient(part));
        assertEquals(List.of(makePart), snapshot.usingIngredient(ingot));
        assertEquals(List.of(smelt), snapshot.usingIngredient(ore));
    }

    @Test
    void nullQueryListsAndMissingFixedResultsProduceReadOnlyEmptyLookups() {
        MutableManager source = new MutableManager();
        Recipe noResult = new FixedStub("no_result", null, List.of());
        Recipe noItem = new FixedStub("no_item", new CustomRecipeResult(null, 1, new PostProcessor[0]), List.of());
        source.types.put(RecipeType.CRAFTING, List.of(noResult, noItem));
        Key known = Key.of("test:known"), absent = Key.of("test:absent");
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.manager, List.of(known));
        assertEquals(Set.of(known), source.resultQueries);
        assertEquals(Set.of(known), source.ingredientQueries);
        assertTrue(snapshot.forResult(known).isEmpty());
        assertTrue(snapshot.usingIngredient(absent).isEmpty());
        assertTrue(snapshot.byType(RecipeType.BREWING).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.forResult(known).add(noItem));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.usingIngredient(absent).add(noItem));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.byType(RecipeType.BREWING).add(noItem));
        assertTrue(RecipeSnapshot.EMPTY.forResult(known).isEmpty());
        assertTrue(RecipeSnapshot.EMPTY.usingIngredient(known).isEmpty());
        assertTrue(RecipeSnapshot.EMPTY.byType(RecipeType.CRAFTING).isEmpty());
    }

    @Test
    void capturingANewGenerationDoesNotChangeAnAlreadyPublishedSnapshot() {
        MutableManager source = new MutableManager();
        Key known = Key.of("test:known");
        Recipe original = recipe("original"), replacement = recipe("replacement");
        source.results.put(known, new ArrayList<>(List.of(original)));
        RecipeSnapshot first = RecipeSnapshot.capture(source.manager, List.of(known));
        source.results.get(known).set(0, replacement);
        RecipeSnapshot second = RecipeSnapshot.capture(source.manager, List.of(known));
        assertEquals(List.of(original), first.forResult(known));
        assertEquals(List.of(replacement), second.forResult(known));
    }

    private static Ingredient ingredient(Key custom, Key vanilla) {
        return Ingredient.of(List.of(), custom == null ? Set.of() : Set.of(UniqueKey.create(custom)),
                vanilla == null ? Set.of() : Set.of(UniqueKey.create(vanilla)), custom != null);
    }

    private static Recipe recipe(String name) { return new StubRecipe(name, List.of()); }

    private static Recipe fixed(String name, Key result, Ingredient... ingredients) {
        BuildableItem item = new BuildableItem() {
            @Override public Key id() { return result; }
            @Override public Item buildItem(ItemBuildContext context, int count) {
                throw new AssertionError("Snapshot capture must not build result items");
            }
        };
        return new FixedStub(name, new CustomRecipeResult(item, 1, new PostProcessor[0]), List.of(ingredients));
    }

    private static class StubRecipe implements Recipe {
        private final Key id;
        private final List<Ingredient> ingredients;

        private StubRecipe(String name, List<Ingredient> ingredients) {
            this.id = Key.of("test:" + name);
            this.ingredients = ingredients;
        }

        @Override public List<Ingredient> ingredientsInUse() { return this.ingredients; }
        @Override public Key id() { return this.id; }
        @Override public RecipeType type() { return RecipeType.CRAFTING; }
        @Override public Key serializerType() { throw new AssertionError("Unexpected serialization"); }
        @Override public boolean matches(RecipeInput input) { throw new AssertionError("Unexpected match"); }
        @Override public void takeInput(RecipeInput input, int amount) { throw new AssertionError("Unexpected input mutation"); }
        @Override public Item assemble(RecipeInput input, ItemBuildContext context) {
            throw new AssertionError("Snapshot capture must not assemble recipes");
        }
    }

    private static final class FixedStub extends StubRecipe implements FixedResultRecipe {
        private final CustomRecipeResult result;

        private FixedStub(String name, CustomRecipeResult result, List<Ingredient> ingredients) {
            super(name, ingredients);
            this.result = result;
        }

        @Override public CustomRecipeResult result() { return this.result; }
    }

    private static final class MutableManager {
        private final Map<RecipeType, List<Recipe>> types = new EnumMap<>(RecipeType.class);
        private final Map<Key, List<Recipe>> results = new HashMap<>();
        private final Map<Key, List<Recipe>> ingredients = new HashMap<>();
        private final Set<Key> resultQueries = new HashSet<>(), ingredientQueries = new HashSet<>();
        private boolean rejectCalls;
        private final RecipeManager manager = (RecipeManager) Proxy.newProxyInstance(
                RecipeManager.class.getClassLoader(), new Class<?>[]{RecipeManager.class}, (proxy, method, args) -> {
                    if (rejectCalls) throw new AssertionError("Published snapshot accessed the live manager");
                    return switch (method.getName()) {
                        case "recipesByType" -> types.get((RecipeType) args[0]);
                        case "recipeByResult" -> {
                            resultQueries.add((Key) args[0]);
                            yield results.get((Key) args[0]);
                        }
                        case "recipeByIngredient" -> {
                            ingredientQueries.add((Key) args[0]);
                            yield ingredients.get((Key) args[0]);
                        }
                        default -> throw new AssertionError("Unexpected manager call: " + method);
                    };
                });
    }
}

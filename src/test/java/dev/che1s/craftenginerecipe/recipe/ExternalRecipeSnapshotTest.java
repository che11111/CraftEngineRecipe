package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalRecipeSnapshotTest {
    private static final RecipeItem TOMATO = RecipeItem.of("garden:tomato");
    private static final RecipeItem STEW = RecipeItem.of("garden:stew");
    private static final RecipeItem CARROT = RecipeItem.of("minecraft:carrot");

    @Test
    void preservesAllRecipesForOneOutputAndTheirSourceOrder() {
        RecipeEntry first = entry("custom", "garden:first", List.of(), List.of(result(STEW)));
        RecipeEntry second = entry("addon", "garden:second", List.of(), List.of(result(STEW)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(second, first));
        assertEquals(List.of(second, first), snapshot.results().get(ExternalRecipeSnapshot.key(STEW)));
        assertEquals(List.of(second, first), snapshot.stations().get("garden:pot"));
        assertEquals(List.of(second, first), snapshot.entries());
    }

    @Test
    void indexesEveryAlternativeAndNonDecorativeRole() {
        RecipeItem knife = RecipeItem.of("garden:knife");
        RecipeItem bowl = RecipeItem.of("minecraft:bowl");
        RecipeItem coal = RecipeItem.of("minecraft:coal");
        RecipeItem clock = RecipeItem.of("minecraft:clock");
        RecipeEntry recipe = entry("custom", "garden:first", List.of(
                ingredient(RecipeIngredient.Role.INGREDIENT, TOMATO, CARROT),
                ingredient(RecipeIngredient.Role.TOOL, knife),
                ingredient(RecipeIngredient.Role.CONTAINER, bowl),
                ingredient(RecipeIngredient.Role.FUEL, coal),
                ingredient(RecipeIngredient.Role.INFO, clock)), List.of(result(STEW)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(recipe));
        for (RecipeItem input : List.of(TOMATO, CARROT, knife, bowl, coal)) {
            assertEquals(List.of(recipe), snapshot.usages().get(ExternalRecipeSnapshot.key(input)));
            assertTrue(snapshot.items().containsKey(ExternalRecipeSnapshot.key(input)));
        }
        assertFalse(snapshot.usages().containsKey(ExternalRecipeSnapshot.key(clock)));
        assertFalse(snapshot.items().containsKey(ExternalRecipeSnapshot.key(clock)));
    }

    @Test
    void deduplicatesMatchingKeysWithinARecipeWithoutReorderingRecipesOrOutputs() {
        RecipeEntry first = entry("custom", "garden:first", List.of(
                ingredient(RecipeIngredient.Role.INGREDIENT, TOMATO, TOMATO),
                ingredient(RecipeIngredient.Role.CONTAINER, TOMATO)),
                List.of(result(STEW), result(CARROT), result(STEW)));
        RecipeEntry second = entry("custom", "garden:second", List.of(ingredient(RecipeIngredient.Role.INGREDIENT, TOMATO)),
                List.of(result(STEW)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(first, second));
        assertEquals(List.of(first, second), snapshot.results().get(ExternalRecipeSnapshot.key(STEW)));
        assertEquals(List.of(first, second), snapshot.usages().get(ExternalRecipeSnapshot.key(TOMATO)));
        assertEquals(List.of(first), snapshot.results().get(ExternalRecipeSnapshot.key(CARROT)));
        assertEquals(List.of(ExternalRecipeSnapshot.key(STEW), ExternalRecipeSnapshot.key(CARROT)),
                List.copyOf(snapshot.results().keySet()));
    }

    @Test
    void separatesOpaqueProviderIdentitiesAndUnifiesSharedCraftEngineItems() {
        RecipeItem firstProvider = RecipeItem.of("first", "garden:tomato");
        RecipeItem secondProvider = RecipeItem.of("second", "garden:tomato");
        assertNotEquals(ExternalRecipeSnapshot.key(firstProvider), ExternalRecipeSnapshot.key(secondProvider));
        assertNotEquals(ExternalRecipeSnapshot.key(firstProvider), ExternalRecipeSnapshot.key(TOMATO));
        assertNotEquals(ExternalRecipeSnapshot.key(RecipeItem.of("first", "a:b")),
                ExternalRecipeSnapshot.key(RecipeItem.of("first", "a/b")));
        assertEquals(Key.of("garden:tomato"), ExternalRecipeSnapshot.key(TOMATO));
        assertEquals(Key.of("minecraft:carrot"), ExternalRecipeSnapshot.key(CARROT));

        RecipeItem sameCeIdentity = new RecipeItem("craftengine", "garden:tomato", null, "另一来源的图标", List.of());
        RecipeEntry producer = entry("private-kitchen", "garden:produce", List.of(), List.of(result(sameCeIdentity)));
        RecipeEntry consumer = entry("custom", "garden:consume", List.of(ingredient(RecipeIngredient.Role.INGREDIENT, TOMATO)), List.of(result(STEW)));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(List.of(producer, consumer));
        assertEquals(List.of(producer), snapshot.results().get(Key.of("garden:tomato")));
        assertEquals(List.of(consumer), snapshot.usages().get(Key.of("garden:tomato")));
    }

    @Test
    void snapshotsOwnTheirListsAndExposeImmutableMaps() {
        RecipeEntry recipe = entry("custom", "garden:first", List.of(), List.of(result(STEW)));
        List<RecipeEntry> source = new ArrayList<>(List.of(recipe));
        ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(source);
        source.clear();
        assertEquals(List.of(recipe), snapshot.entries());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.results().get(ExternalRecipeSnapshot.key(STEW)).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.workstations().clear());
    }

    @Test
    void catalogPrefersAnAvailableIconInEitherSourceOrderWithoutSharingRecipeOverrides() {
        RecipeItem reference = new RecipeItem("private", "stew", null, "<gold>Recipe A", List.of("A lore"));
        RecipeItem exact = new RecipeItem("private", "stew", new TestStack(3), "<green>Recipe B", List.of("B lore"));
        RecipeEntry first = entry("custom", "garden:first", List.of(), List.of(result(reference)));
        RecipeEntry second = entry("api", "garden:second", List.of(), List.of(result(exact)));
        for (List<RecipeEntry> sources : List.of(List.of(first, second), List.of(second, first))) {
            ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(sources);
            RecipeItem catalog = snapshot.items().get(ExternalRecipeSnapshot.key(reference));
            assertNotNull(catalog.icon());
            assertEquals(3, catalog.icon().getAmount());
            assertNull(catalog.name());
            assertNull(catalog.lore());
            catalog.icon().setAmount(8);
            assertEquals(3, catalog.icon().getAmount());
        }
        assertEquals("<gold>Recipe A", first.display().results().getFirst().item().name());
        assertEquals(List.of("B lore"), second.display().results().getFirst().item().lore());
    }

    @Test
    void rejectsDifferentIdentitiesThatWouldShareAnInternalKeyInEitherSourceOrder() {
        RecipeItem foreign = RecipeItem.of("private", "opaque");
        RecipeItem collidingCe = RecipeItem.of("craftengine", ExternalRecipeSnapshot.key(foreign).toString());
        RecipeEntry first = entry("custom", "garden:first", List.of(), List.of(result(foreign)));
        RecipeEntry second = entry("api", "garden:second", List.of(), List.of(result(collidingCe)));
        for (List<RecipeEntry> sources : List.of(List.of(first, second), List.of(second, first))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ExternalRecipeSnapshot.compile(sources));
            assertTrue(error.getMessage().contains("Item identity collision"));
            assertTrue(error.getMessage().contains("private/opaque"));
            assertTrue(error.getMessage().contains("craftengine/cer_external:"));
        }
    }

    @Test
    void treatsCraftEngineAndMinecraftProvidersAsAliasesForVanillaIdsInEitherSourceOrder() {
        RecipeItem vanilla = RecipeItem.of("minecraft", "minecraft:carrot");
        RecipeItem throughCe = new RecipeItem("craftengine", "minecraft:carrot", new TestStack(2), "CE recipe name", null);
        RecipeEntry first = entry("custom", "garden:first",
                List.of(ingredient(RecipeIngredient.Role.INGREDIENT, vanilla, throughCe)),
                List.of(result(vanilla), result(throughCe)));
        RecipeEntry second = entry("api", "garden:second",
                List.of(ingredient(RecipeIngredient.Role.CONTAINER, throughCe)), List.of(result(throughCe)));
        Key key = Key.of("minecraft:carrot");
        for (List<RecipeEntry> sources : List.of(List.of(first, second), List.of(second, first))) {
            ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.compile(sources);
            assertEquals(sources, snapshot.results().get(key));
            assertEquals(sources, snapshot.usages().get(key));
            assertEquals("minecraft", snapshot.items().get(key).provider());
            assertEquals(2, snapshot.items().get(key).icon().getAmount());
        }
        assertEquals("craftengine", throughCe.provider());
        assertEquals("CE recipe name", throughCe.name());
    }

    private static RecipeIngredient ingredient(RecipeIngredient.Role role, RecipeItem... items) {
        return new RecipeIngredient(List.of(items), 1, -1, role);
    }

    private static RecipeResult result(RecipeItem item) { return new RecipeResult(item, 1, -1, null); }

    private static RecipeEntry entry(String source, String id, List<RecipeIngredient> ingredients, List<RecipeResult> results) {
        DisplayRecipe recipe = new DisplayRecipe(id,
                new RecipeWorkstation("garden:pot", RecipeItem.of("minecraft:cauldron"), "烹饪锅"),
                new RecipeLayout(6, null, -1, -1, -1, -1, -1), ingredients, results, List.of(), null, null);
        return new RecipeEntry(source, null, recipe, () -> true);
    }

    private static final class TestStack extends ItemStack {
        private int amount;
        private TestStack(int amount) { this.amount = amount; }
        @Override public int getAmount() { return this.amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public TestStack clone() { return new TestStack(this.amount); }
    }
}

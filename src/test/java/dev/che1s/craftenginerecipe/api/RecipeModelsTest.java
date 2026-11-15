package dev.che1s.craftenginerecipe.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecipeModelsTest {
    @Test
    void preservesIdentitiesOverridesAndImmutableChoices() {
        assertEquals("minecraft", RecipeItem.of("minecraft:carrot").provider());
        assertEquals("craftengine", RecipeItem.of("garden:carrot").provider());
        assertEquals("cookery", RecipeItem.of("cookery", "private_item").provider());
        assertNull(RecipeItem.of("garden:carrot").lore());
        List<String> lore = new ArrayList<>();
        RecipeItem item = new RecipeItem("craftengine", "garden:carrot", null, "", lore);
        lore.add("changed later");
        assertEquals(List.of(), item.lore());
        assertEquals("", item.name());
        assertThrows(UnsupportedOperationException.class, () -> item.lore().add("mutation"));
        List<RecipeItem> choices = new ArrayList<>(List.of(item));
        RecipeIngredient ingredient = new RecipeIngredient(choices, 2, -1, null);
        choices.clear();
        assertEquals(List.of(item), ingredient.choices());
        assertEquals(RecipeIngredient.Role.INGREDIENT, ingredient.role());
    }

    @Test
    void copiesIconsAtRegistrationAndEveryRead() {
        TestStack original = new TestStack(3);
        RecipeItem item = RecipeItem.stack("cookery", "private_item", original);
        original.setAmount(9);
        assertEquals(3, item.icon().getAmount());
        item.icon().setAmount(15);
        assertEquals(3, item.icon().getAmount());
        assertNotSame(item.icon(), item.icon());
    }

    @Test
    void validatesIdAmountsSlotsAndControlConflictsWithoutLimitingActualQuantitiesToStackSize() {
        RecipeItem item = RecipeItem.of("minecraft:carrot");
        assertThrows(IllegalArgumentException.class, () -> RecipeItem.of("Not A Provider", "id"));
        assertThrows(IllegalArgumentException.class, () -> RecipeItem.of(""));
        assertThrows(IllegalArgumentException.class, () -> RecipeItem.of("craftengine", "missing_namespace"));
        assertThrows(IllegalArgumentException.class, () -> RecipeItem.of("minecraft", "garden:carrot"));
        assertThrows(IllegalArgumentException.class, () -> new RecipeWorkstation("missing_namespace", item, null));
        assertThrows(IllegalArgumentException.class, () -> new RecipeIngredient(List.of(item), 0, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new RecipeResult(item, 1, -2, null));
        assertThrows(IllegalArgumentException.class, () -> new RecipeResult(item, 1, 54, null));
        assertThrows(IllegalArgumentException.class, () -> new RecipeLayout(0, null, -1, -1, -1, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new RecipeLayout(3, null, 4, 4, -1, -1, -1));
        assertEquals(128, new RecipeIngredient(List.of(item), 128, -1, null).amount());
        assertEquals(new RecipeLayout(6, null, 4, 49, 45, 48, 53), RecipeLayout.defaults());
    }

    @Test
    void validatesContentAgainstTheActualLayoutAndAcceptsEmptyLists() {
        RecipeItem item = RecipeItem.of("minecraft:carrot");
        RecipeLayout layout = new RecipeLayout(2, null, 4, -1, -1, -1, -1);
        RecipeWorkstation station = new RecipeWorkstation("garden:pot", item, null);
        assertThrows(IllegalArgumentException.class, () -> recipe(station, layout,
                List.of(new RecipeIngredient(List.of(item), 1, 4, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> recipe(station, layout,
                List.of(new RecipeIngredient(List.of(item), 1, 18, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> recipe(station, layout,
                List.of(new RecipeIngredient(List.of(item), 1, 10, null)), List.of(new RecipeResult(item, 1, 10, null))));
        List<RecipeResult> overflow = java.util.Collections.nCopies(18, new RecipeResult(item, 1, -1, null));
        assertEquals(18, recipe(station, layout, List.of(), overflow).results().size());
        assertEquals(List.of(), recipe(station, layout, List.of(), List.of()).ingredients());
    }

    @Test
    void appliesBothPermissionAndPredicateOnEachQuery() {
        AtomicInteger called = new AtomicInteger();
        DisplayRecipe recipe = new DisplayRecipe("garden:stew", new RecipeWorkstation("garden:pot", RecipeItem.of("minecraft:cauldron"), null),
                null, List.of(), List.of(), List.of(), "garden.special", player -> called.incrementAndGet() == 1);
        assertFalse(recipe.canView(null));
        assertFalse(recipe.canView(player(false)));
        assertEquals(0, called.get());
        assertTrue(recipe.canView(player(true)));
        assertFalse(recipe.canView(player(true)));
    }

    private static DisplayRecipe recipe(RecipeWorkstation station, RecipeLayout layout, List<RecipeIngredient> ingredients, List<RecipeResult> results) {
        return new DisplayRecipe("garden:stew", station, layout, ingredients, results, List.of(), null, null);
    }

    private static Player player(boolean permission) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{ Player.class },
                (proxy, method, arguments) -> method.getName().equals("hasPermission") ? permission : null);
    }

    /** 避免启动真实服务器 ItemFactory；该边界测试只验证克隆和数量。 */
    private static final class TestStack extends ItemStack {
        private int amount;
        private TestStack(int amount) { this.amount = amount; }
        @Override public int getAmount() { return this.amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public TestStack clone() { return new TestStack(this.amount); }
    }
}

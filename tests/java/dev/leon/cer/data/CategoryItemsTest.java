package dev.leon.cer.data;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CategoryItemsTest {
    private static Category category(String id, String... members) {
        return new Category(Key.of(id), id, List.of(), Key.of("minecraft:chest"),
                List.of(members), 0, false, context -> true);
    }

    @Test
    void deniedCrossNamespaceChildCannotLeakIntoSearch() {
        Category root = category("public:root", "public:apple", "#private:child");
        Category child = category("private:child", "private:secret");
        assertEquals(Set.of(Key.of("public:apple")), CategoryItems.visible(List.of(root),
                Map.of(root.id(), root, child.id(), child), c -> !c.id().namespace().equals("private"), c -> Set.of()));
    }

    @Test
    void handlesCyclesMissingChildrenAndHiddenItems() {
        Category root = category("test:root", "#test:child", "test:hidden", "test:visible", "#test:missing");
        Category child = category("test:child", "#test:root", "test:visible", "test:child_item");
        assertEquals(Set.of(Key.of("test:visible"), Key.of("test:child_item")), CategoryItems.visible(List.of(root),
                Map.of(root.id(), root, child.id(), child), c -> true, c -> Set.of("test:hidden")));
    }

    @Test
    void hiddenInOneCategoryMayStillBeVisibleThroughAnotherPermittedCategory() {
        Category a = category("test:a", "test:shared");
        Category b = category("test:b", "test:shared");
        assertEquals(Set.of(Key.of("test:shared")), CategoryItems.visible(List.of(a, b),
                Map.of(a.id(), a, b.id(), b), c -> true,
                c -> c == a ? Set.of("test:shared") : Set.of()));
    }

    @Test
    void standaloneWildcardHidesAllDirectItemsButKeepsChildAndOtherPaths() {
        Category gui = category("farmersdelight:gui", "farmersdelight:button", "farmersdelight:shared", "#farmersdelight:child");
        Category display = category("farmersdelight:item_display", "farmersdelight:decoration");
        Category child = category("farmersdelight:child", "farmersdelight:child_item");
        Category food = category("farmersdelight:food", "farmersdelight:shared", "farmersdelight:tomato");
        assertEquals(Set.of(Key.of("farmersdelight:child_item"), Key.of("farmersdelight:shared"), Key.of("farmersdelight:tomato")),
                CategoryItems.visible(List.of(gui, display, food),
                        Map.of(gui.id(), gui, display.id(), display, child.id(), child, food.id(), food), c -> true,
                        c -> c == gui || c == display ? Set.of("*") : Set.of()));
    }

    @Test
    void itemFilterSupportsExactIdsAndStandaloneWildcardWithoutPrefixMatching() {
        Key item = Key.of("farmersdelight:button");
        assertTrue(CategoryItems.isHidden(Set.of("*"), item));
        assertTrue(CategoryItems.isHidden(Set.of(item.toString()), item));
        assertFalse(CategoryItems.isHidden(null, item));
        assertFalse(CategoryItems.isHidden(Set.of(), item));
        assertFalse(CategoryItems.isHidden(Set.of("farmersdelight:*"), item));
        assertFalse(CategoryItems.isHidden(Set.of("farmersdelight:other"), item));
    }

    @Test
    void hiddenParentPrunesDescendantsWhileIndependentVisiblePathsRemain() {
        Category root = category("test:root", "#test:hidden", "#test:public", "test:normal");
        Category hidden = category("test:hidden", "test:secret", "#test:child");
        Category child = category("test:child", "test:nested", "test:shared");
        Category visible = category("test:public", "test:shared");
        assertEquals(Set.of(Key.of("test:normal"), Key.of("test:shared")),
                CategoryItems.visible(List.of(root), Map.of(root.id(), root, hidden.id(), hidden,
                                child.id(), child, visible.id(), visible),
                        category -> !category.id().equals(hidden.id()), category -> Set.of()));
    }

    @Test
    void hiddenRootCannotExposeAnyDescendantItems() {
        Category root = category("test:root", "test:secret", "#test:child");
        Category child = category("test:child", "test:nested");
        assertTrue(CategoryItems.visible(List.of(root), Map.of(root.id(), root, child.id(), child),
                category -> !category.id().equals(root.id()), category -> Set.of()).isEmpty());
    }
}

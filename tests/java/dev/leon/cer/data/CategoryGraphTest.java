package dev.leon.cer.data;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CategoryGraphTest {
    @Test
    void compiledMembersKeepOrderDuplicatesAndRemainIndependentOfLiveCategoriesAndTheirMap() {
        Category root = category("test:root", "test:second", "#test:child", "test:first", "test:second", "#test:child");
        Category child = category("test:child", "test:nested");
        Map<Key, Category> source = new LinkedHashMap<>();
        source.put(root.id(), root);
        source.put(child.id(), child);
        CategoryGraph graph = CategoryGraph.compile(source);
        source.clear();
        root.members().clear();
        child.members().clear();
        List<CategoryGraph.Member> members = graph.members(root.id());
        assertEquals(List.of("test:second", "test:child", "test:first", "test:second", "test:child"),
                members.stream().map(CategoryGraph.Member::reference).toList());
        assertEquals(List.of(false, true, false, false, true), members.stream().map(CategoryGraph.Member::category).toList());
        assertEquals(Set.of(Key.of("test:second"), Key.of("test:first"), Key.of("test:nested")),
                graph.visible(List.of(root), ignored -> true, ignored -> Set.of()));
        assertThrows(UnsupportedOperationException.class, members::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> graph.members(Key.of("test:missing")).add(members.getFirst()));
    }

    @Test
    void unusualReferencesKeepCraftEngineKeyOfSemanticsInsteadOfIntroducingNewValidationOrTrimming() {
        Category category = category("test:root", "stone", ":path", "test:", "UPPER:Odd Value", "#",
                "# test:child", "  test:item  ", "test:a:extra");
        CategoryGraph graph = CategoryGraph.compile(Map.of(category.id(), category));
        List<String> references = List.of("stone", ":path", "test:", "UPPER:Odd Value", "",
                " test:child", "  test:item  ", "test:a:extra");
        assertEquals(references, graph.members(category.id()).stream().map(CategoryGraph.Member::reference).toList());
        assertEquals(references.stream().map(Key::of).toList(),
                graph.members(category.id()).stream().map(CategoryGraph.Member::key).toList());
        assertEquals(List.of(false, false, false, false, true, true, false, false),
                graph.members(category.id()).stream().map(CategoryGraph.Member::category).toList());
    }

    @Test
    void skipsOnlyNullOrBlankMembersAndReturnsReadOnlyVisibleItems() {
        Category root = category("test:root", null, "", " ", "\t", "test:item", "test:item");
        CategoryGraph graph = CategoryGraph.compile(Map.of(root.id(), root));
        assertEquals(List.of("test:item", "test:item"),
                graph.members(root.id()).stream().map(CategoryGraph.Member::reference).toList());
        Set<Key> visible = graph.visible(List.of(root), ignored -> true, ignored -> Set.of());
        assertEquals(Set.of(Key.of("test:item")), visible);
        assertThrows(UnsupportedOperationException.class, visible::clear);
    }

    @Test
    void duplicateChildReferencesAndCyclesTerminateWhileApplyingCurrentPermissionAndHidingPredicates() {
        Category root = category("test:root", "test:item", "#test:child", "#test:child", "#test:missing");
        Category child = category("test:child", "#test:root", "test:private");
        CategoryGraph graph = CategoryGraph.compile(Map.of(root.id(), root, child.id(), child));
        assertEquals(Set.of(Key.of("test:item")), graph.visible(List.of(root),
                category -> !category.id().equals(child.id()), ignored -> Set.of()));
        assertEquals(Set.of(Key.of("test:private")), graph.visible(List.of(root), ignored -> true,
                category -> category.id().equals(root.id()) ? Set.of("*") : Set.of()));
    }

    private static Category category(String id, String... members) {
        return new Category(Key.of(id), id, List.of(), Key.of("minecraft:chest"),
                new ArrayList<>(Arrays.asList(members)), 0, false, ignored -> true);
    }
}

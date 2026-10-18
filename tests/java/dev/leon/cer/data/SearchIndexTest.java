package dev.leon.cer.data;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {
    private static Key key(String value) { return Key.of("test:" + value); }

    @Test
    void ranksExactMatchesAheadOfEarlierPartialMatches() {
        Map<Key, String> names = new LinkedHashMap<>();
        names.put(key("fruit_bowl"), "Other");
        names.put(key("dessert"), "Fruit cake");
        names.put(key("apple"), "Fruit");
        names.put(key("fruit"), "Other");
        assertEquals(List.of(key("fruit"), key("apple"), key("dessert"), key("fruit_bowl")),
                SearchIndex.fromNames(names).search(" FRUIT ", 10));
    }

    @Test
    void supportsChineseFullPinyinAndInitials() {
        SearchIndex index = SearchIndex.fromNames(Map.of(key("eggplant"), "茄子"));
        for (String query : List.of("茄子", "qie", "zi", "qiez", "qz")) {
            assertEquals(List.of(key("eggplant")), index.search(query, 10), query);
        }
    }

    @Test
    void translationKeysAreSearchableEvenWhenTheItemHasADifferentCustomName() {
        Key item = key("custom_output");
        var index = SearchIndex.fromNames(Map.of(item, "茄子"),
                Map.of(item, "item.example.eggplant"));
        for (String query : List.of("茄子", "qz", "ITEM.EXAMPLE.EGGPLANT", "example.egg")) {
            assertEquals(List.of(item), index.search(query, 10), query);
        }
    }

    @Test
    void translatedNamesAndKeysRetainPermissionFilteringAndMatchRanking() {
        Map<Key, String> names = new LinkedHashMap<>();
        names.put(key("hidden"), "茄子");
        names.put(key("other"), "Other");
        names.put(key("eggplant"), "Visible eggplant");
        Map<Key, String> keys = Map.of(key("hidden"), "item.example.eggplant", key("other"), "item.example.eggplant");
        var index = SearchIndex.fromNames(names, keys);
        assertEquals(List.of(key("eggplant"), key("other")),
                index.search("eggplant", 2, id -> !id.equals(key("hidden"))));
        assertEquals(List.of(key("other")),
                index.search("item.example", 1, id -> !id.equals(key("hidden"))));
    }

    @Test
    void filtersBeforeApplyingLimitSoHiddenMatchesCannotCrowdOutVisibleOnes() {
        Map<Key, String> names = new LinkedHashMap<>();
        names.put(key("secret"), "Apple");
        names.put(key("public"), "Apple pie");
        names.put(key("public_second"), "Apple tart");
        Set<Key> visible = Set.of(key("public"), key("public_second"));
        assertEquals(List.of(key("public")), SearchIndex.fromNames(names).search("apple", 1, visible::contains));
    }

    @Test
    void preservesRankingUnderLargeMatchCountsAndReturnsImmutableResults() {
        Map<Key, String> names = new LinkedHashMap<>();
        for (int i = 0; i < 10_000; i++) names.put(key("apple_" + i), "Apple pie " + i);
        names.put(key("apple"), "Exact ID at end");
        List<Key> result = SearchIndex.fromNames(names).search("apple", 3);
        assertEquals(List.of(key("apple"), key("apple_0"), key("apple_1")), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(key("extra")));
    }

    @Test
    void handlesBlankNamesEmptyQueriesAndNonpositiveLimits() {
        Map<Key, String> names = new LinkedHashMap<>();
        names.put(key("fallback"), null);
        SearchIndex index = SearchIndex.fromNames(names);
        assertEquals(List.of(key("fallback")), index.search("fallback", 1));
        assertTrue(index.search(null, 10).isEmpty());
        assertTrue(index.search(" ", 10).isEmpty());
        assertTrue(index.search("fallback", 0).isEmpty());
        assertTrue(index.search("fallback", -1).isEmpty());
    }

    @Test
    void categorySearchKeepsRankingAndPinyin() {
        Map<Key, String> names = new LinkedHashMap<>();
        names.put(key("foo_other"), "Other");
        names.put(key("foo"), "精确分类");
        names.put(key("vegetables"), "蔬菜");
        CategoryIndex index = CategoryIndex.fromNames(names);
        assertEquals(List.of(key("foo")), index.search("foo", 1));
        assertEquals(List.of(key("vegetables")), index.search("shucai", 10));
        assertEquals(List.of(key("vegetables")), index.search("sc", 10));
        assertTrue(index.search("foo", -1).isEmpty());
    }
}

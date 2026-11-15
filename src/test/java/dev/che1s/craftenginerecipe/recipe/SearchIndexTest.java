package dev.che1s.craftenginerecipe.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchIndexTest {
    @Test
    void searchesChineseNamesAndEnglishIdWords() {
        Key wheat = Key.of("minecraft:wheat");
        Key sword = Key.of("minecraft:wooden_sword");
        SearchIndex index = SearchIndex.fromNames(Map.of(
                wheat, "小麦",
                sword, "wooden_sword"));

        assertEquals(List.of(wheat), index.search("小麦", 10));
        assertEquals(List.of(sword), index.search("wooden sword", 10));
    }
}

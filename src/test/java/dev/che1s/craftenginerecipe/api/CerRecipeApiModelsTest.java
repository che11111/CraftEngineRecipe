package dev.che1s.craftenginerecipe.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CerRecipeApiModelsTest {
    @Test
    void localizedTextUsesExactLanguageThenLanguageFamilyThenEnglishThenWildcard() {
        LocalizedText text = LocalizedText.of(new LinkedHashMap<>(Map.of(
                "zh_CN", "中文",
                "en_US", "English",
                "ja_JP", "日本語")));

        assertEquals("中文", text.resolve("zh_CN"));
        assertEquals("中文", text.resolve("zh-CN"));
        assertEquals("English", text.resolve("de_DE"));
        assertEquals("first", LocalizedText.of(Map.of("fr_FR", "first")).resolve("de_DE"));
        assertEquals("fallback", LocalizedText.plain("fallback").resolve("zh_CN"));
    }

    @Test
    void localizedTextMatchesARequestedRegionToItsBaseLanguage() {
        LocalizedText text = LocalizedText.of(Map.of("zh", "简体"));

        assertEquals("简体", text.resolve("zh_CN"));
        assertEquals("简体", text.resolve("zh_TW"));
    }

    @Test
    void apiRecordsCopyCollectionsAndRequireResultsAndWorkstation() {
        List<LocalizedText> lore = new ArrayList<>(List.of(LocalizedText.plain("说明")));
        ApiItem item = new ApiItem("garden:tomato", LocalizedText.plain("番茄"), lore, 3);
        lore.clear();
        assertEquals(1, item.lore().size());
        assertEquals(3, item.amount());
        assertThrows(UnsupportedOperationException.class, () -> item.lore().clear());

        List<ApiItem> ingredients = new ArrayList<>(List.of(item));
        ApiRecipe recipe = new ApiRecipe("tomato_soup", new ApiItem("minecraft:cauldron"),
                ingredients, List.of(new ApiItem("garden:soup")));
        ingredients.clear();
        assertEquals(1, recipe.ingredients().size());
        assertNull(recipe.note());

        assertThrows(IllegalArgumentException.class,
                () -> new ApiRecipe("recipe", new ApiItem("minecraft:cauldron"), List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new ApiRecipe("recipe", null, List.of(), List.of(item)));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiRecipe("Upper Case", new ApiItem("minecraft:cauldron"),
                        List.of(), List.of(item)));
        assertThrows(IllegalArgumentException.class, () -> new ApiItem("missing_namespace"));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiItem("minecraft:stone", null, List.of(), 0));
    }

    @Test
    void noteCanContainNameOrLoreAndEmptyNotesAreRecognized() {
        assertTrue(new ApiNote(null).isEmpty());
        assertFalse(new ApiNote(LocalizedText.plain("提示")).isEmpty());
        assertFalse(new ApiNote(null, LocalizedText.plain("说明")).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> new ApiNote(null, LocalizedText.plain("说明")).lore().clear());
    }
}

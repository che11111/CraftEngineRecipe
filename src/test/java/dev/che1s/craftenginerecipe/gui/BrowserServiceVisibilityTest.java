package dev.che1s.craftenginerecipe.gui;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserServiceVisibilityTest {
    private static final Key HONEY_BOTTLE = Key.of("minecraft:honey_bottle");

    @Test
    void unlistedRegisteredMinecraftItemsCanBeWorkstationOutputs() {
        assertTrue(BrowserService.nativeItemVisible(
                HONEY_BOTTLE, false, true, false, false, false));
        assertTrue(BrowserService.nativeItemVisible(
                HONEY_BOTTLE, false, true, false, true, true));
    }

    @Test
    void catalogAndHiddenCategoryRulesStillTakePrecedence() {
        assertTrue(BrowserService.nativeItemVisible(
                HONEY_BOTTLE, true, true, true, false, false));
        assertFalse(BrowserService.nativeItemVisible(
                HONEY_BOTTLE, false, true, true, false, false));
        assertFalse(BrowserService.nativeItemVisible(
                Key.of("example:item"), false, true, false, false, false));
    }

    @Test
    void ordinaryVanillaItemsFollowTheirNamespaceVisibility() {
        assertTrue(BrowserService.nativeItemVisible(
                Key.of("minecraft:diamond"), false, false, false, false, false));
        assertTrue(BrowserService.nativeItemVisible(
                Key.of("minecraft:diamond"), false, false, false, true, true));
        assertFalse(BrowserService.nativeItemVisible(
                Key.of("minecraft:diamond"), false, false, false, true, false));
    }

    @Test
    void vanillaItemsCategoryContainsOnlyMinecraftNamespaceIds() {
        List<Key> ids = List.of(Key.of("minecraft:grass_block"), Key.of("minecraft:diamond"),
                Key.of("farmersdelight:cooking_pot"), Key.of("test:custom"));
        Set<Key> vanilla = BrowserService.vanillaItemKeys(ids, List.of());

        assertTrue(vanilla.contains(Key.of("minecraft:grass_block")));
        assertTrue(vanilla.contains(Key.of("minecraft:diamond")));
        assertFalse(vanilla.contains(Key.of("farmersdelight:cooking_pot")));
        assertFalse(vanilla.contains(Key.of("test:custom")));
    }

    @Test
    void vanillaItemsCanSortAlphabeticallyAndHideItemsWithoutCerRecipes() {
        List<Key> ids = List.of(Key.of("minecraft:zombie_spawn_egg"), Key.of("minecraft:diamond"),
                Key.of("minecraft:grass_block"));

        Set<Key> prepared = BrowserService.prepareVanillaItems(ids, true, true,
                id -> !id.value().equals("grass_block"));

        assertEquals(List.of(Key.of("minecraft:diamond"), Key.of("minecraft:zombie_spawn_egg")),
                List.copyOf(prepared));
    }

    @Test
    void hideWithoutRecipesOnlyHidesItemsWithNeitherResultsNorUses() {
        assertTrue(BrowserService.shouldHideWithoutRecipes(true, false, false));
        assertFalse(BrowserService.shouldHideWithoutRecipes(true, true, false));
        assertFalse(BrowserService.shouldHideWithoutRecipes(true, false, true));
        assertFalse(BrowserService.shouldHideWithoutRecipes(false, false, false));
    }

    @Test
    void bundledAndCultivationRecipesBypassPartialCeCatalogVisibility() {
        assertTrue(BrowserService.isStandaloneMinecraftRecipe("minecraft_data:wooden_sword"));
        assertTrue(BrowserService.isStandaloneMinecraftRecipe("minecraft_cultivation:wheat"));
        assertTrue(BrowserService.isStandaloneMinecraftRecipe(
                "cultivation_kaleidoscopecookery:tomato_crop"));
        assertTrue(BrowserService.isStandaloneMinecraftRecipe(
                "farmersdelight:skillet/default/minecraft_3aminecraft_3abeef_3d_3eminecraft_3acooked_5fbeef"));
        assertFalse(BrowserService.isStandaloneMinecraftRecipe("example:custom"));
    }
}

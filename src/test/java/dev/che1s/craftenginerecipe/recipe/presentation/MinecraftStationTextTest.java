package dev.che1s.craftenginerecipe.recipe.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinecraftStationTextTest {
    @Test
    void mapsEveryNativeRecipePageToItsEditableLanguageKey() {
        assertEquals("station.crafting", MinecraftStationText.key("minecraft:crafting"));
        assertEquals("station.campfire_cooking", MinecraftStationText.key("minecraft:campfire_cooking"));
        assertEquals("station.smithing", MinecraftStationText.key("minecraft:smithing"));
        assertNull(MinecraftStationText.key("minecraft:cultivation"));
        assertNull(MinecraftStationText.key("farmersdelight:cooking_pot"));
    }

    @Test
    void resolvesThePlayersTextAndRejectsAMissingTranslation() {
        assertEquals("Crafting Table", MinecraftStationText.resolve(
                "minecraft:crafting", key -> "Crafting Table"));
        assertNull(MinecraftStationText.resolve("minecraft:crafting", key -> key));
    }
}

package dev.leon.cer.gui;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SoundsTest {
    @Test
    void legacyAliasesKeepCompoundRegistryPathSegmentsAndIgnoreCase() {
        Map<String, String> available = Map.of(
                "minecraft:ui.button.click", "click",
                "minecraft:block.iron_door.open", "door",
                "minecraft:block.note_block.bass", "note",
                "minecraft:entity.zombie_villager.ambient", "villager",
                "minecraft:music_disc.13", "disc");
        assertEquals("click", Sounds.resolve("UI_BUTTON_CLICK", key -> available.get(key.toString())));
        assertEquals("door", Sounds.resolve("block_iron_door_open", key -> available.get(key.toString())));
        assertEquals("note", Sounds.resolve("BLOCK_NOTE_BLOCK_BASS", key -> available.get(key.toString())));
        assertEquals("villager", Sounds.resolve("ENTITY_ZOMBIE_VILLAGER_AMBIENT", key -> available.get(key.toString())));
        assertEquals("disc", Sounds.resolve("MUSIC_DISC_13", key -> available.get(key.toString())));
    }

    @Test
    void registryKeysWinBeforeLegacyAliasesAndUnknownSoundsStaySilent() {
        Map<String, String> available = Map.of(
                "minecraft:ui_button_click", "direct",
                "minecraft:ui.button.click", "alias",
                "example:menu-click", "custom");
        assertEquals("direct", Sounds.resolve("UI_BUTTON_CLICK", key -> available.get(key.toString())));
        assertEquals("alias", Sounds.resolve("ui.button.click", key -> available.get(key.toString())));
        assertEquals("custom", Sounds.resolve("example:menu-click", key -> available.get(key.toString())));
        assertNull(Sounds.resolve("UNKNOWN_SOUND", key -> available.get(key.toString())));
        assertNull(Sounds.resolve("invalid:bad key", key -> available.get(key.toString())));
        assertNull(Sounds.resolve("BLOCK_IRON_DOOR_OPEN", key -> available.get(key.toString())));
    }

    @Test
    void trMenuStyleClickUsesExplicitVolumeAndPitchForConfigAndActions() {
        var expected = new Sounds.SoundSpec("UI_BUTTON_CLICK", 1.0f, 2.0f);
        assertEquals(expected, Sounds.parse("UI_BUTTON_CLICK-1-2", 0.7f));
        assertEquals(expected, Sounds.parse("UI_BUTTON_CLICK-1-2", 1.0f));
    }

    @Test
    void acceptsMinecraftKeysWithParametersAndOptionalNamespace() {
        assertEquals(new Sounds.SoundSpec("minecraft:ui.button.click", 1.0f, 2.0f),
                Sounds.parse("minecraft:ui.button.click-1-2", 0.7f));
        assertEquals(new Sounds.SoundSpec("ui.button.click", 0.5f, 0.8f),
                Sounds.parse("ui.button.click-0.5-0.8", 0.7f));
    }

    @Test
    void plainSoundsPreserveExistingConfigAndActionDefaults() {
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 0.7f, 1.0f),
                Sounds.parse("UI_BUTTON_CLICK", 0.7f));
        assertEquals(new Sounds.SoundSpec("minecraft:ui.button.click", 1.0f, 1.0f),
                Sounds.parse("minecraft:ui.button.click", 1.0f));
    }

    @Test
    void volumeOnlyAndWhitespaceAreSupported() {
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 0.5f, 1.0f),
                Sounds.parse(" UI_BUTTON_CLICK - 0.5 ", 0.7f));
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 1.0f, 2.0f),
                Sounds.parse(" UI_BUTTON_CLICK - 1 - 2 ", 0.7f));
    }

    @Test
    void zeroValuesRemainValidAndNegativeValuesAreClamped() {
        assertEquals(new Sounds.SoundSpec("BLOCK_LEVER_CLICK", 1.0f, 0.0f),
                Sounds.parse("BLOCK_LEVER_CLICK-1-0", 1.0f));
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 0.0f, 2.0f),
                Sounds.parse("UI_BUTTON_CLICK-0-2", 0.7f));
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 0.0f, 0.0f),
                Sounds.parse("UI_BUTTON_CLICK--1--2", 0.7f));
    }

    @Test
    void hyphenatedResourceKeysAreNotMistakenForParameters() {
        assertEquals(new Sounds.SoundSpec("example:menu-click", 0.7f, 1.0f),
                Sounds.parse("example:menu-click", 0.7f));
        assertEquals(new Sounds.SoundSpec("example:menu-click", 0.5f, 2.0f),
                Sounds.parse("example:menu-click-0.5-2", 0.7f));
    }

    @Test
    void blankSoundDisablesPlaybackAndOverflowUsesFiniteDefaults() {
        assertNull(Sounds.parse(null, 0.7f));
        assertNull(Sounds.parse(" \t", 0.7f));
        var overflow = Sounds.parse("UI_BUTTON_CLICK-1e999-1e999", 0.7f);
        assertEquals(new Sounds.SoundSpec("UI_BUTTON_CLICK", 0.7f, 1.0f), overflow);
    }
}

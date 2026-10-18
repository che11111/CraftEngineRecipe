package dev.leon.cer.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoundSettingsTest {
    private YamlConfiguration yaml(String text) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private YamlConfiguration withDefaults(String text) throws Exception {
        var config = yaml(text);
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        return config;
    }

    private List<SoundSettings.MenuSound> sound(String spec, float volume) {
        return List.of(new SoundSettings.MenuSound(spec, volume));
    }

    @Test
    void defaultsSeparateCategoryClickFromContentPagingAndBack() {
        var sounds = SoundSettings.load(new YamlConfiguration());
        assertEquals("UI_BUTTON_CLICK-1-2", sounds.changeCategory());
        assertEquals("minecraft:item.book.page_turn", sounds.changePage());
        assertEquals("minecraft:item.book.page_turn", sounds.back());
        assertEquals("minecraft:ui.button.click", sounds.click());
        assertEquals("minecraft:ui.toast.in", sounds.search());
        assertEquals(sound("BLOCK_IRON_DOOR_OPEN-1-2", 0.7f), sounds.openMenu());
        assertEquals(sound("BLOCK_IRON_DOOR_CLOSE-1-2", 0.7f), sounds.closeMenu());
    }

    @Test
    void categoryAndContentSoundsCanBeConfiguredIndependently() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  change-category: UI_BUTTON_CLICK-0.5-2
                  change-page: minecraft:item.book.page_turn-1-0.8
                  click: BLOCK_LEVER_CLICK
                  back: minecraft:item.book.page_turn-0.5-1
                  search: ''
                """));
        assertEquals("UI_BUTTON_CLICK-0.5-2", sounds.changeCategory());
        assertEquals("minecraft:item.book.page_turn-1-0.8", sounds.changePage());
        assertEquals("BLOCK_LEVER_CLICK", sounds.click());
        assertEquals("minecraft:item.book.page_turn-0.5-1", sounds.back());
        assertEquals("", sounds.search());
    }

    @Test
    void newScalarsOverrideAllOldFormatsWithoutCombiningSounds() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  open-menu: minecraft:block.iron_door.open-0.5-0.8
                  close-menu: minecraft:block.iron_door.close-1-2
                  open: UI_BUTTON_CLICK
                Open: ['sound: BLOCK_LEVER_CLICK']
                Close: ['sound: BLOCK_LEVER_CLICK']
                """));
        assertEquals(sound("minecraft:block.iron_door.open-0.5-0.8", 0.7f), sounds.openMenu());
        assertEquals(sound("minecraft:block.iron_door.close-1-2", 0.7f), sounds.closeMenu());
    }

    @Test
    void bundledDefaultsDoNotMaskExplicitLegacyOpenString() throws Exception {
        var sounds = SoundSettings.load(withDefaults("sounds:\n  open: UI_BUTTON_CLICK-1-2\n"));
        assertEquals(sound("UI_BUTTON_CLICK-1-2", 0.7f), sounds.openMenu());
        assertEquals(sound("BLOCK_IRON_DOOR_CLOSE-1-2", 0.7f), sounds.closeMenu());
    }

    @Test
    void legacyActionsOverrideOldStringAndPreserveTheirDefaultVolume() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  open: UI_BUTTON_CLICK
                Open: ['sound: BLOCK_LEVER_CLICK']
                Close: ['sound: minecraft:block.chest.close']
                """));
        assertEquals(sound("BLOCK_LEVER_CLICK", 1.0f), sounds.openMenu());
        assertEquals(sound("minecraft:block.chest.close", 1.0f), sounds.closeMenu());
    }

    @Test
    void emptyNewScalarsDisableDespiteDefaultsAndOldSettings() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  open-menu: ''
                  close-menu: '  '
                  open: UI_BUTTON_CLICK
                Open: ['sound: BLOCK_LEVER_CLICK']
                Close: ['sound: BLOCK_LEVER_CLICK']
                """));
        assertTrue(sounds.openMenu().isEmpty());
        assertTrue(sounds.closeMenu().isEmpty());
    }

    @Test
    void legacyEmptyListsStillDisableDespiteBundledNewDefaults() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  open: UI_BUTTON_CLICK
                Open: []
                Close: []
                """));
        assertTrue(sounds.openMenu().isEmpty());
        assertTrue(sounds.closeMenu().isEmpty());
    }

    @Test
    void legacyActionsFilterCommandsAndPreserveSoundOrder() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                Open:
                  - ' SOUND: UI_BUTTON_CLICK-1-2 '
                  - 'sound: '
                  - 'console: say ignored'
                  - 'close'
                  - 'sound: minecraft:block.chest.open'
                  - ''
                """));
        assertEquals(List.of(new SoundSettings.MenuSound("UI_BUTTON_CLICK-1-2", 1.0f),
                new SoundSettings.MenuSound("minecraft:block.chest.open", 1.0f)), sounds.openMenu());
    }

    @Test
    void disablingOneMenuSoundLeavesOtherSoundUnchanged() throws Exception {
        var sounds = SoundSettings.load(withDefaults("sounds:\n  close-menu: ''\n"));
        assertEquals(sound("BLOCK_IRON_DOOR_OPEN-1-2", 0.7f), sounds.openMenu());
        assertTrue(sounds.closeMenu().isEmpty());
        sounds = SoundSettings.load(withDefaults("sounds:\n  open: ''\n"));
        assertTrue(sounds.openMenu().isEmpty());
        assertEquals(sound("BLOCK_IRON_DOOR_CLOSE-1-2", 0.7f), sounds.closeMenu());
    }

    @Test
    void malformedNewListsStaySilentAndDoNotFallBackToLegacy() throws Exception {
        var sounds = SoundSettings.load(withDefaults("""
                sounds:
                  open-menu: ['sound: UI_BUTTON_CLICK']
                  close-menu: ['console: say ignored']
                Open: ['sound: BLOCK_LEVER_CLICK']
                Close: ['sound: BLOCK_LEVER_CLICK']
                """));
        assertTrue(sounds.openMenu().isEmpty());
        assertTrue(sounds.closeMenu().isEmpty());
    }
}

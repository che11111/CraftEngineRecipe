package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundSettingsTest {
    @TempDir Path directory;

    @Test
    void existingConfigWithoutQuerySoundsUsesSeparateOutcomeDefaults() throws Exception {
        SoundSettings sounds = SoundSettings.load(yaml("""
                sounds:
                  click: minecraft:block.amethyst_block.hit
                  open-menu: minecraft:block.chest.open
                """));

        assertEquals("UI_BUTTON_CLICK-1-2", sounds.querySuccess());
        assertEquals("BLOCK_NOTE_BLOCK_BASS-1-1", sounds.queryFailure());
        assertEquals("minecraft:block.amethyst_block.hit", sounds.click());
        assertEquals("minecraft:block.chest.open", sounds.openMenu().getFirst().spec());
    }

    @Test
    void yamlRoundTripPreservesIndependentCustomOutcomeSounds() throws Exception {
        YamlConfiguration original = yaml("""
                sounds:
                  query-success: 'minecraft:entity.experience_orb.pickup-0.6-1.4'
                  query-failure: 'BLOCK_NOTE_BLOCK_BASS-0.4-0.7'
                  click: 'UI_BUTTON_CLICK-1-2'
                """);
        Path file = directory.resolve("config.yml");
        original.save(file.toFile());

        SoundSettings sounds = SoundSettings.load(YamlConfiguration.loadConfiguration(file.toFile()));

        assertEquals("minecraft:entity.experience_orb.pickup-0.6-1.4", sounds.querySuccess());
        assertEquals("BLOCK_NOTE_BLOCK_BASS-0.4-0.7", sounds.queryFailure());
        assertEquals("UI_BUTTON_CLICK-1-2", sounds.click());
    }

    @Test
    void explicitMuteSurvivesBundledDefaultsAndOldTopLevelKeysAreIgnored() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, """
                sounds:
                  query-success: ''
                  query-failure: ''
                  click: UI_BUTTON_CLICK-1-2
                Open:
                  - 'sound: BLOCK_IRON_DOOR_OPEN-1-2'
                """, StandardCharsets.UTF_8);

        updateFromBundledMenu(file);
        SoundSettings sounds = SoundSettings.load(YamlConfiguration.loadConfiguration(file.toFile()));

        assertEquals("", sounds.querySuccess());
        assertEquals("", sounds.queryFailure());
        assertEquals("UI_BUTTON_CLICK-1-2", sounds.click());
        assertEquals("BLOCK_IRON_DOOR_OPEN-1-2", sounds.openMenu().getFirst().spec());
    }

    @Test
    void upgradingOldConfigAddsQuerySoundsAndKeepsExistingCustomization() throws Exception {
        List<String> layout = List.of("<CCCCCCC>", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX",
                "XXXXXXXXX", "B#L#S#R##");
        Path file = directory.resolve("config.yml");
        Files.writeString(file, """
                # Keep my menu and sounds.
                language: en_US
                browser:
                  layout: ['<CCCCCCC>', 'XXXXXXXXX', 'XXXXXXXXX', 'XXXXXXXXX', 'XXXXXXXXX', 'B#L#S#R##']
                sounds:
                  click: ''
                  change-page: minecraft:block.chiseled_bookshelf.insert
                  open-menu: minecraft:block.chest.open
                  close-menu: minecraft:block.chest.close
                hotkey:
                  enabled: false
                """, StandardCharsets.UTF_8);

        assertTrue(updateFromBundledMenu(file) > 0);
        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(file.toFile());
        SoundSettings sounds = SoundSettings.load(upgraded);

        assertTrue(upgraded.contains("sounds.query-success", true));
        assertTrue(upgraded.contains("sounds.query-failure", true));
        assertEquals("UI_BUTTON_CLICK-1-2", sounds.querySuccess());
        assertEquals("BLOCK_NOTE_BLOCK_BASS-1-1", sounds.queryFailure());
        assertEquals("", sounds.click());
        assertEquals("minecraft:block.chiseled_bookshelf.insert", sounds.changePage());
        assertEquals("minecraft:block.chest.open", sounds.openMenu().getFirst().spec());
        assertEquals("minecraft:block.chest.close", sounds.closeMenu().getFirst().spec());
        assertEquals(layout, upgraded.getStringList("browser.layout"));
        assertEquals("en_US", upgraded.getString("language"));
        assertEquals(false, upgraded.getBoolean("hotkey.enabled"));
        assertTrue(Files.readString(file).contains("Keep my menu and sounds."));

        String firstUpdate = Files.readString(file);
        assertEquals(0, updateFromBundledMenu(file));
        assertEquals(firstUpdate, Files.readString(file));
    }

    @Test
    void upgradingConfigWithOneCustomOutcomeOnlyAddsTheMissingOutcome() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, """
                sounds:
                  query-success: minecraft:block.note_block.bell-0.5-1.3
                """, StandardCharsets.UTF_8);

        updateFromBundledMenu(file);
        SoundSettings sounds = SoundSettings.load(YamlConfiguration.loadConfiguration(file.toFile()));

        assertEquals("minecraft:block.note_block.bell-0.5-1.3", sounds.querySuccess());
        assertEquals("BLOCK_NOTE_BLOCK_BASS-1-1", sounds.queryFailure());
    }

    private static YamlConfiguration yaml(String value) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(value);
        return config;
    }

    private static int updateFromBundledMenu(Path file) throws Exception {
        try (InputStream defaults = SoundSettingsTest.class.getResourceAsStream(
                "/resources/cer_menu_cute/menu/browse.yml")) {
            return YamlDefaultsUpdater.update(file, defaults);
        }
    }
}

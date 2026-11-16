package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigEditorTest {
    @Test
    void writesValidatedGeneralSettings() {
        YamlConfiguration config = new YamlConfiguration();

        assertTrue(PluginConfigEditor.apply(config, new PluginConfigEditor.GeneralSettings(
                "workstations", false, false, true, false, false)));

        assertEquals("workstations", config.getString("browser.default-main-page"));
        assertFalse(config.getBoolean("personalization.enabled"));
        assertFalse(config.getBoolean("browser.remember-last-page"));
        assertTrue(config.getBoolean("hotkey.enabled"));
        assertFalse(config.getBoolean("hotkey.require-sneak"));
        assertFalse(config.getBoolean("check-for-updates"));
    }

    @Test
    void rejectsInvalidOptionsWithoutPartiallyWriting() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("browser.default-main-page", "namespaces");

        assertFalse(PluginConfigEditor.apply(config, new PluginConfigEditor.GeneralSettings(
                "invalid", false, false, false, false, false)));
        assertEquals("namespaces", config.getString("browser.default-main-page"));
    }

    @Test
    void writesDataSettingsOnlyWhenAllNumbersAreInRange() {
        YamlConfiguration config = new YamlConfiguration();
        PluginConfigEditor.DataSettings valid = new PluginConfigEditor.DataSettings(
                true, false, true, false, false, 40, 60, 48, 300);

        assertTrue(PluginConfigEditor.apply(config, valid));
        assertEquals(40, config.getInt("recipes.poll-interval"));
        assertEquals(60, config.getInt("recipe.rotate-interval"));
        assertEquals(48, config.getInt("search.max-length"));
        assertEquals(300, config.getInt("search.max-results"));

        PluginConfigEditor.DataSettings invalid = new PluginConfigEditor.DataSettings(
                true, true, true, true, true, 9, 60, 48, 300);
        assertFalse(PluginConfigEditor.apply(config, invalid));
        assertEquals(40, config.getInt("recipes.poll-interval"));
    }

    @Test
    void numberParserAcceptsOnlyInclusiveConfiguredBounds() {
        assertEquals(10, PluginConfigEditor.parseWithinRange("10", 10, 200));
        assertEquals(200, PluginConfigEditor.parseWithinRange(" 200 ", 10, 200));
        assertNull(PluginConfigEditor.parseWithinRange("9", 10, 200));
        assertNull(PluginConfigEditor.parseWithinRange("201", 10, 200));
        assertNull(PluginConfigEditor.parseWithinRange("many", 10, 200));
    }

    @Test
    void writesValidatedRecipeBookSettings() {
        YamlConfiguration config = new YamlConfiguration();
        List<String> lore = List.of("<#D4AF37>Book</#D4AF37>", "Left: {left}");

        assertTrue(PluginConfigEditor.apply(config,
                new PluginConfigEditor.BookSettings(false, "zh_CN", "  Gold Book  ", lore)));

        assertFalse(config.getBoolean("recipe-book.enabled", true));
        assertEquals("Gold Book", config.getString("recipe-book.name-overrides.zh_CN"));
        assertEquals(lore, config.getStringList("recipe-book.lore-overrides.zh_CN"));

        assertFalse(PluginConfigEditor.apply(config,
                new PluginConfigEditor.BookSettings(true, "zh_CN", " ", lore)));
        assertFalse(config.getBoolean("recipe-book.enabled", true));
    }
}

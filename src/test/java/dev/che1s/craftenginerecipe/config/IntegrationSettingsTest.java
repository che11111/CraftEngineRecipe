package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationSettingsTest {
    @TempDir Path directory;

    @Test
    void integrationModesAreExplicitAndInvalidModeFallsBackToAsk() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("integrations.farmersdelight.mode", "enabled");
        config.set("integrations.kaleidoscope.mode", " DISABLED ");
        config.set("integrations.customcrops.mode", "unknown");

        assertEquals(IntegrationSettings.Mode.ENABLED,
                IntegrationSettings.mode(config, "farmersdelight"));
        assertEquals(IntegrationSettings.Mode.DISABLED,
                IntegrationSettings.mode(config, "kaleidoscope"));
        assertEquals(IntegrationSettings.Mode.ASK,
                IntegrationSettings.mode(config, "customcrops"));
        assertEquals(IntegrationSettings.Mode.ASK,
                IntegrationSettings.mode(config, "not-installed"));
    }

    @Test
    void integrationsCanDeclareLinkedCategoryPresets() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("integrations.farmersdelight.category-presets",
                java.util.List.of("farmersdelight", " farmersdelight ", "extra_content"));

        assertEquals(java.util.List.of("farmersdelight", "extra_content"),
                IntegrationSettings.categoryPresets(config, "farmersdelight"));
        assertEquals(java.util.List.of(),
                IntegrationSettings.categoryPresets(config, "kaleidoscope"));
    }

    @Test
    void dialogChoicesPersistAskAndOnlyApplyCultivationChangesForRealModes() {
        YamlConfiguration config = new YamlConfiguration();

        assertTrue(IntegrationSettings.setModes(config, Map.of(
                "farmersdelight", IntegrationSettings.Mode.ENABLED)));
        assertEquals("enabled", config.getString("integrations.farmersdelight.mode"));
        assertTrue(config.getBoolean("integrations.farmersdelight.cultivation"));

        assertTrue(IntegrationSettings.setModes(config, Map.of(
                "farmersdelight", IntegrationSettings.Mode.ASK)));
        assertEquals("ask", config.getString("integrations.farmersdelight.mode"));
        assertTrue(config.getBoolean("integrations.farmersdelight.cultivation"));

        assertTrue(IntegrationSettings.setModes(config, Map.of(
                "farmersdelight", IntegrationSettings.Mode.DISABLED)));
        assertEquals("disabled", config.getString("integrations.farmersdelight.mode"));
        assertFalse(config.getBoolean("integrations.farmersdelight.cultivation", true));

        assertFalse(IntegrationSettings.setModes(config, Map.of(
                "farmersdelight", IntegrationSettings.Mode.DISABLED)));
        assertFalse(IntegrationSettings.setModes(config, Map.of(
                "not-supported", IntegrationSettings.Mode.ENABLED),
                java.util.Set.of("farmersdelight")));
    }

    @Test
    void categoryPresetModesCanBeChangedInPlace() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("category-presets", java.util.List.of(
                java.util.Map.of("id", "farmersdelight", "mode", "ask"),
                java.util.Map.of("id", "ultramarine", "mode", "ask")));

        assertTrue(IntegrationSettings.setCategoryPresetMode(
                config, "farmersdelight", IntegrationSettings.Mode.DISABLED));
        assertEquals("disabled", config.getMapList("category-presets")
                .getFirst().get("mode"));
        assertEquals("ask", config.getMapList("category-presets")
                .getLast().get("mode"));

        assertTrue(IntegrationSettings.setCategoryPresetMode(
                config, "extension-only", IntegrationSettings.Mode.DISABLED));
        assertEquals("extension-only", config.getMapList("category-presets")
                .getLast().get("id"));
        assertEquals("disabled", config.getMapList("category-presets")
                .getLast().get("mode"));
    }

    @Test
    void integrationSettingsLiveOutsideTheMainConfigFile() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                language: zh_CN
                sources:
                  farmersdelight: false
                """);

        try (var defaults = getClass().getResourceAsStream("/config.yml")) {
            YamlDefaultsUpdater.update(file, defaults);
        }
        YamlConfiguration updated = YamlConfiguration.loadConfiguration(file.toFile());

        assertFalse(updated.isSet("integrations"));

        YamlConfiguration integrations = new YamlConfiguration();
        try (var defaults = getClass().getResourceAsStream("/integrations.yml")) {
            integrations.load(new java.io.InputStreamReader(defaults, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertFalse(integrations.isConfigurationSection("integrations"));
        assertTrue(integrations.getMapList("category-presets").isEmpty());
    }
}

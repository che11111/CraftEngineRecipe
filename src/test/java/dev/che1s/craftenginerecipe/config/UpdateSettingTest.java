package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSettingTest {
    @TempDir
    Path directory;

    @Test
    void oldConfigGainsEnabledCheckerWithoutChangingExistingSettings() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "language: en_US\nhotkey:\n  enabled: false\n");

        update(config);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(config.toFile());

        assertTrue(loaded.getBoolean("check-for-updates"));
        assertEquals("en_US", loaded.getString("language"));
        assertFalse(loaded.getBoolean("hotkey.enabled"));
    }

    @Test
    void disabledCheckerRemainsDisabledAcrossRepeatedDefaultUpdates() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "# My update preference\ncheck-for-updates: false\n");

        update(config);
        String upgraded = Files.readString(config);
        assertEquals(0, update(config));

        assertFalse(YamlConfiguration.loadConfiguration(config.toFile()).getBoolean("check-for-updates"));
        assertEquals(upgraded, Files.readString(config));
        assertTrue(upgraded.contains("My update preference"));
    }

    private static int update(Path config) throws Exception {
        try (InputStream defaults = UpdateSettingTest.class.getResourceAsStream("/config.yml")) {
            return YamlDefaultsUpdater.update(config, defaults);
        }
    }
}

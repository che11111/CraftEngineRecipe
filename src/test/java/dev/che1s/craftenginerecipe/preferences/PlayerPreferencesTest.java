package dev.che1s.craftenginerecipe.preferences;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreferencesTest {
    @TempDir Path directory;

    @Test
    void defaultsChoicesAndPlayerValuesRoundTripThroughSqlite() {
        UUID player = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("cute", "rough"));
        config.set("personalization.default-menu-pack", "cer_menu_rough");
        config.set("personalization.default-remember-last-page", false);
        config.set("browser.default-main-page", "workstations");

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_map"));

            assertTrue(preferences.enabled());
            assertEquals(List.of("cer_menu_cute", "cer_menu_rough"), preferences.menuPacks());
            assertEquals("cer_menu_rough", preferences.menuPack(player));
            assertFalse(preferences.rememberLastPage(player));
            assertEquals(PlayerPreferences.LandingPage.WORKSTATIONS, preferences.landingPage(player));
            assertTrue(preferences.setMenuPack(player, "cute"));
            preferences.setRememberLastPage(player, true);
            preferences.setLandingPage(player, PlayerPreferences.LandingPage.NAMESPACES);
            assertTrue(preferences.setLanguage(player, "en_us"));
        }

        try (PlayerPreferences restored = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            restored.reload(config, List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_map"));

            assertEquals("cer_menu_cute", restored.menuPack(player));
            assertTrue(restored.rememberLastPage(player));
            assertEquals(PlayerPreferences.LandingPage.NAMESPACES, restored.landingPage(player));
            assertEquals("en_US", restored.language(player, "zh_CN"));
        }
    }

    @Test
    void disabledOrUnavailableDefaultFallsBackToFirstConfiguredChoice() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", false);
        config.set("personalization.menu-packs", List.of("missing", "vanilla"));
        config.set("personalization.default-menu-pack", "map");

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_vanilla", "cer_menu_map"));

            assertFalse(preferences.enabled());
            assertEquals(List.of("cer_menu_vanilla"), preferences.menuPacks());
            assertEquals("cer_menu_vanilla", preferences.defaultMenuPack());
        }
    }

    @Test
    void serverLandingPageOverridesStoredPlayerChoiceWhenPersonalizationIsDisabled() {
        UUID player = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("cute"));
        config.set("browser.default-main-page", "namespaces");

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_cute"));
            preferences.setLandingPage(player, PlayerPreferences.LandingPage.NAMESPACES);

            config.set("personalization.enabled", false);
            config.set("browser.default-main-page", "workstations");
            preferences.reload(config, List.of("cer_menu_cute"));

            assertFalse(preferences.enabled());
            assertEquals(PlayerPreferences.LandingPage.WORKSTATIONS, preferences.landingPage(player));
        }
    }

    @Test
    void existingDatabaseIsMigratedWithLandingPageColumn() throws Exception {
        UUID player = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + this.directory.resolve("player-preferences.db").toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE player_preferences (
                        player_uuid TEXT PRIMARY KEY,
                        menu_pack TEXT,
                        remember_last_page INTEGER,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO player_preferences VALUES ('" + player
                    + "', 'cer_menu_rough', 1, 1)");
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("rough"));
        config.set("browser.default-main-page", "workstations");

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_rough"));

            assertEquals("cer_menu_rough", preferences.menuPack(player));
            assertTrue(preferences.rememberLastPage(player));
            assertEquals(PlayerPreferences.LandingPage.WORKSTATIONS, preferences.landingPage(player));
            preferences.setLandingPage(player, PlayerPreferences.LandingPage.NAMESPACES);
            assertEquals("en_US", preferences.language(player, "en_US"));
            assertTrue(preferences.setLanguage(player, "zh_CN"));
        }

        try (PlayerPreferences restored = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            restored.reload(config, List.of("cer_menu_rough"));
            assertEquals(PlayerPreferences.LandingPage.NAMESPACES, restored.landingPage(player));
            assertEquals("zh_CN", restored.language(player, "en_US"));
        }
    }

    @Test
    void unknownLanguageFallsBackToEnglishAndInvalidOverridesAreRejected() {
        UUID player = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("cute"));

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_cute"));
            assertEquals("en_US", preferences.language(player, "pt_BR"));
            assertFalse(preferences.setLanguage(player, "pt_BR"));
            assertTrue(preferences.setLanguage(player, "fr_FR"));
            assertEquals("fr_FR", preferences.language(player, "en_US"));
        }
    }

    @Test
    void languageOverridesAreIsolatedPerPlayer() {
        UUID chinese = UUID.randomUUID();
        UUID english = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("cute"));

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_cute"));
            assertTrue(preferences.setLanguage(chinese, "zh_CN"));
            assertTrue(preferences.setLanguage(english, "en_US"));
        }

        try (PlayerPreferences restored = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            restored.reload(config, List.of("cer_menu_cute"));
            assertEquals("zh_CN", restored.language(chinese, "en_US"));
            assertEquals("en_US", restored.language(english, "zh_CN"));
        }
    }

    @Test
    void preferencesAndGuiSessionsShareTheSameDatabase() {
        UUID player = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("personalization.enabled", true);
        config.set("personalization.menu-packs", List.of("cute"));
        config.set("browser.default-main-page", "workstations");
        long savedAt = 123456789L;

        try (PlayerPreferences preferences = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            preferences.reload(config, List.of("cer_menu_cute"));
            preferences.setMenuPack(player, "cute");
            preferences.saveSession(player, "snapshot: minecraft:test", savedAt);
        }

        try (PlayerPreferences restored = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            restored.reload(config, List.of("cer_menu_cute"));
            assertEquals("cer_menu_cute", restored.menuPack(player));
            assertEquals("snapshot: minecraft:test",
                    restored.loadSessions().get(player).snapshot());
            assertEquals(savedAt, restored.loadSessions().get(player).savedAt());
            restored.deleteSession(player);
        }

        try (PlayerPreferences restored = new PlayerPreferences(this.directory,
                Logger.getLogger(PlayerPreferencesTest.class.getName()))) {
            assertFalse(restored.loadSessions().containsKey(player));
        }
    }
}

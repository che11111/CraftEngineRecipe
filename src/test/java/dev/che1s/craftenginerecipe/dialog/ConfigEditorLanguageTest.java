package dev.che1s.craftenginerecipe.dialog;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditorLanguageTest {
    @Test
    void everyConfigEditorControlAndMessageIsLocalized() throws Exception {
        List<String> keys = List.of(
                "ui.management.config-name",
                "ui.management.config-lore",
                "ui.management.config-summary",
                "ui.config-editor.general-title",
                "ui.config-editor.data-title",
                "ui.config-editor.recipe-book-title",
                "ui.config-editor.description",
                "ui.config-editor.save",
                "ui.config-editor.cancel",
                "ui.config-editor.landing-page",
                "ui.config-editor.personalization",
                "ui.config-editor.remember-page",
                "ui.config-editor.hotkey",
                "ui.config-editor.hotkey-sneak",
                "ui.config-editor.updates",
                "ui.config-editor.source-craftengine",
                "ui.config-editor.source-minecraft",
                "ui.config-editor.source-minecraft-cultivation",
                "ui.config-editor.source-craftengine-cultivation",
                "ui.config-editor.auto-reload",
                "ui.config-editor.poll-interval",
                "ui.config-editor.rotate-interval",
                "ui.config-editor.search-max-length",
                "ui.config-editor.search-max-results",
                "ui.config-editor.open-recipe-book",
                "ui.config-editor.recipe-book-enabled",
                "ui.config-editor.recipe-book-name",
                "ui.config-editor.recipe-book-lore",
                "ui.personalization.book-action-auto-left",
                "ui.personalization.book-action-auto-right",
                "ui.personalization.book-action-search",
                "ui.personalization.book-action-offhand-usage",
                "ui.personalization.book-action-target-usage",
                "ui.personalization.book-action-personalization",
                "ui.personalization.restore-defaults",
                "messages.offhand-no-usage",
                "messages.target-block-no-usage",
                "messages.personalization-reset",
                "messages.config-invalid",
                "messages.config-save-started",
                "messages.config-saved",
                "messages.config-save-failed");
        for (String locale : dev.che1s.craftenginerecipe.i18n.LanguageManager.supportedLocales()) {
            YamlConfiguration config = new YamlConfiguration();
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("lang/" + locale + ".yml")) {
                assertNotNull(input, locale);
                config.load(new java.io.InputStreamReader(
                        input, java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String key : keys) assertTrue(config.isSet(key), locale + ": " + key);
        }
    }
}

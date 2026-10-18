package dev.leon.cer.i18n;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LanguageManagerTest {
    @TempDir Path folder;

    private LanguageManager manager() {
        return new LanguageManager(folder, path -> getClass().getClassLoader().getResourceAsStream(path),
                Logger.getLogger("LanguageManagerTest"));
    }

    @Test
    void extractsBothLanguagesAndSwitchesUsingAliases() {
        LanguageManager lang = manager();
        lang.load("EN-us");
        assertEquals("en_US", lang.locale());
        assertEquals("All items", lang.text("ui.common.all-items"));
        assertTrue(Files.isRegularFile(folder.resolve("lang/zh_CN.yml")));
        assertTrue(Files.isRegularFile(folder.resolve("lang/en_US.yml")));
        lang.load("zh-cn");
        assertEquals("zh_CN", lang.locale());
        assertNotEquals("All items", lang.text("ui.common.all-items"));
    }

    @Test
    void preservesEditsAndReloadsThemWithBundledFallback() throws Exception {
        LanguageManager lang = manager();
        lang.load("en_US");
        Path file = folder.resolve("lang/en_US.yml");
        String custom = "ui:\n  common:\n    all-items: Custom items\n";
        Files.writeString(file, custom);
        lang.load("en_US");
        assertEquals(custom, Files.readString(file));
        assertEquals("Custom items", lang.text("ui.common.all-items"));
        assertTrue(lang.text("messages.no-permission").contains("permission"));
        Files.writeString(file, custom.replace("Custom items", "Updated items"));
        lang.load("en_US");
        assertEquals("Updated items", lang.text("ui.common.all-items"));
    }

    @Test
    void partialCustomLanguageUsesEnglishFallback() throws Exception {
        LanguageManager lang = manager();
        lang.load("zh_CN");
        Files.writeString(folder.resolve("lang/fr_FR.yml"), "ui:\n  common:\n    all-items: Tous les objets\n");
        lang.load("fr_FR");
        assertEquals("fr_FR", lang.locale());
        assertEquals("Tous les objets", lang.text("ui.common.all-items"));
        assertTrue(lang.text("messages.no-permission").contains("permission"));
    }

    @Test
    void malformedYamlFallsBackToBundledLanguage() throws Exception {
        LanguageManager lang = manager();
        lang.load("en_US");
        Files.writeString(folder.resolve("lang/en_US.yml"), "messages: [broken\n");
        lang.load("en_US");
        assertEquals("All items", lang.text("ui.common.all-items"));
        assertFalse(lang.list("ui.content.item-lore").isEmpty());
    }

    @Test
    void invalidTypesFallBackButEmptyListsAreAllowed() throws Exception {
        LanguageManager lang = manager();
        lang.load("en_US");
        Files.writeString(folder.resolve("lang/en_US.yml"), """
                ui:
                  common:
                    all-items: [wrong type]
                  content:
                    item-lore: 42
                  category-bar:
                    icon-lore: []
                """);
        lang.load("en_US");
        assertEquals("All items", lang.text("ui.common.all-items"));
        assertFalse(lang.list("ui.content.item-lore").isEmpty());
        assertTrue(lang.list("ui.category-bar.icon-lore").isEmpty());
    }

    @Test
    void missingAndUnsafeLocalesFallBackWithoutReadingOutsideLang() throws Exception {
        LanguageManager lang = manager();
        Files.writeString(folder.resolve("outside.yml"), "prefix: Outside\n");
        for (String locale : List.of("../outside", "/tmp/outside", "missing_LOCALE", "")) {
            lang.load(locale);
            assertEquals("zh_CN", lang.locale());
            assertNotEquals("Outside", lang.text("prefix"));
        }
        lang.load(null);
        assertEquals("zh_CN", lang.locale());
    }

    @Test
    void oldDefaultUiFollowsLanguageButCustomOverridesRemain() {
        LanguageManager lang = manager();
        lang.load("zh_CN");
        YamlConfiguration config = new YamlConfiguration();
        config.set("browser.title", lang.text("ui.browser.title"));
        config.set("content.item-lore", lang.list("ui.content.item-lore"));
        lang.load("en_US");
        assertEquals(lang.text("ui.browser.title"), lang.uiText(config, "browser.title"));
        assertEquals(lang.list("ui.content.item-lore"), lang.uiList(config, "content.item-lore"));
        config.set("browser.title", "My custom title");
        config.set("content.item-lore", List.of("My custom lore"));
        assertEquals("My custom title", lang.uiText(config, "browser.title"));
        assertEquals(List.of("My custom lore"), lang.uiList(config, "content.item-lore"));
        config.set("content.item-lore", List.of());
        assertTrue(lang.uiList(config, "content.item-lore").isEmpty());
    }

    @Test
    void componentArgumentsCannotInjectFormattingOrExpandRecursively() {
        LanguageManager lang = manager();
        lang.load("en_US");
        String argument = "<red>{other}$\\";
        var component = lang.component("messages.category-not-found", "id", argument, "other", "expanded");
        assertEquals("Category not found: " + argument,
                PlainTextComponentSerializer.plainText().serialize(component));
        assertEquals("{other} $\\ {missing}",
                LanguageManager.format("{id} {literal} {missing}", "id", "{other}",
                        "other", "expanded", "literal", "$\\"));
    }

    @Test
    void prefixedMessagesAndUnknownKeysRemainReadable() {
        LanguageManager lang = manager();
        lang.load("en_US");
        String message = PlainTextComponentSerializer.plainText().serialize(lang.message("messages.player-only"));
        assertTrue(message.startsWith("[CraftEngineRecipe] "));
        assertTrue(message.contains("player"));
        assertEquals("missing.key", lang.text("missing.key"));
        assertTrue(lang.list("missing.list").isEmpty());
    }

    @Test
    void bundledLanguagesHaveMatchingKeysTypesAndPlaceholders() throws Exception {
        Map<String, Object> chinese = resourceValues("zh_CN");
        Map<String, Object> english = resourceValues("en_US");
        assertEquals(chinese.keySet(), english.keySet());
        Pattern placeholders = Pattern.compile("\\{[A-Za-z0-9_-]+}|<(?:name|count|category|keyword|permission)>");
        for (var entry : chinese.entrySet()) {
            Object other = english.get(entry.getKey());
            assertEquals(entry.getValue() instanceof List<?>, other instanceof List<?>, entry.getKey());
            assertEquals(placeholders.matcher(entry.getValue().toString()).results().map(m -> m.group()).sorted().toList(),
                    placeholders.matcher(other.toString()).results().map(m -> m.group()).sorted().toList(), entry.getKey());
        }
    }

    private Map<String, Object> resourceValues(String locale) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = getClass().getClassLoader().getResourceAsStream("lang/" + locale + ".yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        Map<String, Object> leaves = new TreeMap<>();
        yaml.getValues(true).forEach((key, value) -> {
            if (value instanceof String || value instanceof List<?>) leaves.put(key, value);
        });
        return leaves;
    }
}

package dev.che1s.craftenginerecipe.i18n;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageDefaultsUpdateTest {
    private static final List<String> LOCALES = List.of("zh_CN", "en_US");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void createLanguageDirectory() throws Exception {
        Files.createDirectories(this.dataFolder.resolve("lang"));
    }

    @Test
    void loadingAddsMissingShortcutKeysToBothFilesAndPreservesCustomText() throws Exception {
        for (String locale : LOCALES) {
            Files.writeString(this.file(locale), """
                    # A server owner's existing translations.
                    prefix: 'Custom %s prefix '
                    ui:
                      content:
                        info:
                          name: 'Custom %s instructions'
                        search:
                          name: 'Custom %s search'
                    """.formatted(locale, locale, locale), StandardCharsets.UTF_8);
            assertFalse(this.readFile(locale).contains("ui.content.info.lore"));
        }

        LanguageManager language = this.manager();
        // 未启用的内置语言文件也必须一并更新。
        language.load("en_US");

        for (String locale : LOCALES) {
            YamlConfiguration updated = this.readFile(locale);
            YamlConfiguration bundled = bundled(locale);
            for (String path : List.of("ui.content.info.lore", "ui.content.hand.name", "ui.content.hand.lore",
                    "ui.content.workstations.name", "ui.content.workstations.lore",
                    "ui.workstations.categories.smithing")) {
                assertTrue(updated.contains(path), locale + ": " + path);
                assertEquals(bundled.get(path), updated.get(path), locale + ": " + path);
            }
            assertEquals("Custom " + locale + " prefix ", updated.getString("prefix"));
            assertEquals("Custom " + locale + " instructions", updated.getString("ui.content.info.name"));
            assertEquals("Custom " + locale + " search", updated.getString("ui.content.search.name"));

            language.load(locale);
            assertEquals("Custom " + locale + " prefix ", language.text("prefix"));
            assertEquals("Custom " + locale + " instructions", language.text("ui.content.info.name"));
            assertEquals(bundled.getStringList("ui.content.info.lore"), language.list("ui.content.info.lore"));
        }
    }

    @Test
    void existingEmptyTextAndLoreListsRemainDeliberateOverrides() throws Exception {
        for (String locale : LOCALES) {
            Files.writeString(this.file(locale), """
                    prefix: ''
                    ui:
                      content:
                        info:
                          lore: []
                        hand:
                          name: 'My hand button'
                          lore: []
                        search:
                          name: ''
                    """, StandardCharsets.UTF_8);
        }

        LanguageManager language = this.manager();
        for (String locale : LOCALES) {
            language.load(locale);
            YamlConfiguration updated = this.readFile(locale);
            assertEquals("", updated.getString("prefix"));
            assertEquals("", updated.getString("ui.content.search.name"));
            assertEquals("My hand button", updated.getString("ui.content.hand.name"));
            for (String path : List.of("ui.content.info.lore", "ui.content.hand.lore")) {
                assertTrue(updated.isList(path));
                assertEquals(List.of(), updated.getStringList(path));
                assertEquals(List.of(), language.list(path));
            }
            assertEquals("", language.text("prefix"));
            assertEquals("", language.text("ui.content.search.name"));
            assertEquals("My hand button", language.text("ui.content.hand.name"));
        }
    }

    @Test
    void loadingAgainDoesNotRewriteCompleteLanguageFiles() throws Exception {
        LanguageManager language = this.manager();
        language.load("zh_CN");
        Map<String, byte[]> before = new LinkedHashMap<>();
        Map<String, FileTime> timestamps = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            before.put(locale, Files.readAllBytes(this.file(locale)));
            Files.setLastModifiedTime(this.file(locale), FileTime.fromMillis(946684800000L));
            timestamps.put(locale, Files.getLastModifiedTime(this.file(locale)));
        }

        language.load("en_US");
        language.load("zh_CN");

        for (String locale : LOCALES) {
            assertArrayEquals(before.get(locale), Files.readAllBytes(this.file(locale)), locale);
            assertEquals(timestamps.get(locale), Files.getLastModifiedTime(this.file(locale)), locale);
        }
    }

    @Test
    void missingTranslationKeysCanUseReadableCodeFallbacks() {
        LanguageManager language = this.manager();
        language.load("zh_CN");

        assertEquals("开启", language.textOr("ui.missing.boolean", "开启"));
        assertEquals("仅管理员", PlainTextComponentSerializer.plainText().serialize(
                language.componentOr("ui.missing.visibility", "仅管理员")));
        assertEquals("按钮名称<name>", PlainTextComponentSerializer.plainText().serialize(
                language.component("ui.button-editor.name")));
        assertEquals("按钮提示<lore>（每行一条）", PlainTextComponentSerializer.plainText().serialize(
                language.component("ui.button-editor.lore")));
        assertEquals("顶层分类提示<lore>（每行一条）",
                PlainTextComponentSerializer.plainText().serialize(
                        language.component("ui.unapplied-editor.icon-lore")));
        assertEquals("子分类提示<lore>（每行一条）",
                PlainTextComponentSerializer.plainText().serialize(
                        language.component("ui.unapplied-editor.sub-icon-lore")));
    }

    @Test
    void existingLanguageAndUiValuesStayExact() throws Exception {
        String oldTitle = "<white><image:cer:main>Old title <category>";
        Files.writeString(this.file("zh_CN"), """
                ui:
                  browser:
                    title: '<white><image:cer:main>Old title <category>'
                  recipe:
                    title-smithing: '<gray>自定义锻造</gray>'
                """, StandardCharsets.UTF_8);
        LanguageManager language = this.manager();

        language.load("zh_CN");
        YamlConfiguration menu = new YamlConfiguration();
        menu.set("browser.title", oldTitle);
        menu.set("recipe.title-smithing", "<gray>自定义锻造</gray>");

        assertEquals(oldTitle, language.text("ui.browser.title"));
        assertEquals(oldTitle, language.uiText(menu, "browser.title"));
        assertEquals("<gray>自定义锻造</gray>", language.text("ui.recipe.title-smithing"));
        assertEquals("<gray>自定义锻造</gray>", language.uiText(menu, "recipe.title-smithing"));
    }

    @Test
    void malformedLanguageFilesStayUntouchedAndRuntimeUsesBundledTranslations() throws Exception {
        byte[] malformed = "# Keep the owner's broken file intact.\nprefix: custom\nui: [unterminated\n"
                .getBytes(StandardCharsets.UTF_8);
        for (String locale : LOCALES) {
            Files.write(this.file(locale), malformed);
        }

        LanguageManager language = this.manager();
        for (String locale : LOCALES) {
            language.load(locale);
            YamlConfiguration bundled = bundled(locale);
            assertEquals(locale, language.locale());
            assertEquals(bundled.getString("prefix"), language.text("prefix"));
            assertEquals(bundled.getString("ui.content.hand.name"), language.text("ui.content.hand.name"));
            assertEquals(bundled.getStringList("ui.content.info.lore"), language.list("ui.content.info.lore"));
            for (String saved : LOCALES) {
                assertArrayEquals(malformed, Files.readAllBytes(this.file(saved)), saved);
            }
        }
    }

    private LanguageManager manager() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new LanguageManager(this.dataFolder,
                resource -> LanguageDefaultsUpdateTest.class.getClassLoader().getResourceAsStream(resource), logger);
    }

    private Path file(String locale) {
        return this.dataFolder.resolve("lang").resolve(locale + ".yml");
    }

    private YamlConfiguration readFile(String locale) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(this.file(locale).toFile());
        return yaml;
    }

    private static YamlConfiguration bundled(String locale) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = LanguageDefaultsUpdateTest.class.getClassLoader().getResourceAsStream("lang/" + locale + ".yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }
}

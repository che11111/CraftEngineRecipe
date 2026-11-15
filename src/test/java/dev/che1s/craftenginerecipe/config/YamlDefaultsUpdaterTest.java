package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlDefaultsUpdaterTest {
    @TempDir Path directory;

    @Test
    void addsAbsentValuesWithDefaultCommentsAndKeepsUserValuesAndComments() throws Exception {
        Path file = write("""
                # 管理员自己的注释 / User comment
                content:
                  hand:
                    char: '' # deliberately disabled
                    material: minecraft:stone
                sounds:
                  click: ''
                hotkey:
                  enabled: false
                search:
                  max-results: 0
                  languages: []
                """);
        String defaults = """
                content:
                  hand:
                    char: H
                    material: minecraft:paper
                    # 新模型 / New model
                    item-model: cer:palm_blue_cuff
                  # 说明按钮 / Help button
                  info:
                    char: I
                sounds:
                  click: minecraft:ui.button.click
                hotkey:
                  enabled: true
                search:
                  max-results: 200
                  languages: [zh_cn, en_us]
                """;

        assertEquals(2, update(file, defaults));
        Map<String, Object> values = read(file);
        Map<String, Object> content = map(values, "content");
        assertEquals("", map(content, "hand").get("char"));
        assertEquals("minecraft:stone", map(content, "hand").get("material"));
        assertEquals("cer:palm_blue_cuff", map(content, "hand").get("item-model"));
        assertEquals("I", map(content, "info").get("char"));
        assertEquals("", map(values, "sounds").get("click"));
        assertEquals(false, map(values, "hotkey").get("enabled"));
        assertEquals(0, map(values, "search").get("max-results"));
        assertEquals(List.of(), map(values, "search").get("languages"));
        String result = Files.readString(file);
        assertTrue(result.contains("管理员自己的注释 / User comment"));
        assertTrue(result.contains("deliberately disabled"));
        assertTrue(result.contains("新模型 / New model"));
        assertTrue(result.contains("说明按钮 / Help button"));
    }

    @Test
    void doesNotReplaceScalarOrListParentsButPopulatesExplicitEmptySections() throws Exception {
        Path file = write("content: ''\nsounds: []\nsearch: {}\n");

        assertEquals(1, update(file, "content:\n  hand: H\nsounds:\n  click: beep\nsearch:\n  max-results: 200\n"));
        Map<String, Object> values = read(file);
        assertEquals("", values.get("content"));
        assertEquals(List.of(), values.get("sounds"));
        assertEquals(200, map(values, "search").get("max-results"));
    }

    @Test
    void explicitNullValuesAndParentsSurviveAnUpdate() throws Exception {
        Path file = write("language: null\ncontent: ~\nunknown: null\n");

        assertEquals(1, update(file, "language: zh_CN\ncontent:\n  hand: H\nhotkey:\n  enabled: true\n"));
        Map<String, Object> values = read(file);
        assertTrue(values.containsKey("language"));
        assertNull(values.get("language"));
        assertTrue(values.containsKey("content"));
        assertNull(values.get("content"));
        assertTrue(values.containsKey("unknown"));
        assertNull(values.get("unknown"));
    }

    @Test
    void noMissingDefaultsLeavesBytesAndModificationTimeUntouched() throws Exception {
        String original = "# Keep exact format\r\nlanguage:    'en_US' # custom\r\n";
        Path file = write(original);
        FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(file, modified);

        assertEquals(0, update(file, "language: zh_CN\n"));
        assertEquals(original, Files.readString(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
    }

    @Test
    void malformedUserYamlIsNeverOverwritten() throws Exception {
        String original = "content: [broken\n";
        Path file = write(original);

        assertThrows(InvalidConfigurationException.class, () -> update(file, "language: zh_CN\n"));
        assertEquals(original, Files.readString(file));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void duplicateUserKeysAreNeverSilentlyCollapsed() throws Exception {
        String original = "language: en_US\nlanguage: zh_CN\n";
        Path file = write(original);

        assertThrows(InvalidConfigurationException.class, () -> update(file, "hotkey: true\n"));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void invalidBundledDefaultsDoNotChangeUserFile() throws Exception {
        String original = "language: en_US\n";
        Path file = write(original);

        assertThrows(InvalidConfigurationException.class, () -> update(file, "content: [broken\n"));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void nonMappingUserDocumentIsNeverOverwritten() throws Exception {
        String original = "- custom\n- data\n";
        Path file = write(original);

        assertThrows(InvalidConfigurationException.class, () -> update(file, "language: zh_CN\n"));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void inheritedUserValuesAreNotReplacedAndAnchorsRemainValid() throws Exception {
        Path file = write("base: &base\n  click: custom\nsounds:\n  <<: *base\n");

        assertEquals(1, update(file, "sounds:\n  click: beep\n  back: book\n"));
        Map<String, Object> values = read(file);
        assertEquals(Map.of("click", "custom"), values.get("base"));
        assertEquals(Map.of("click", "custom", "back", "book"), values.get("sounds"));
    }

    @Test
    void extendsInheritedSectionsLocallyWithoutChangingTheirAnchorSource() throws Exception {
        Path file = write("base: &base\n  hand:\n    char: Z\ncontent:\n  <<: *base\n");

        assertEquals(1, update(file, "content:\n  hand:\n    char: H\n    item-model: cer:palm_blue_cuff\n"));
        Map<String, Object> values = read(file);
        assertEquals(Map.of("hand", Map.of("char", "Z")), values.get("base"));
        assertEquals(Map.of("hand", Map.of("char", "Z", "item-model", "cer:palm_blue_cuff")), values.get("content"));
        assertEquals(0, update(file, "content:\n  hand:\n    char: H\n    item-model: cer:palm_blue_cuff\n"));
    }

    @Test
    void commentsOnlyDocumentsKeepTheirHeader() throws Exception {
        Path file = write("# User's instructions\n# 自定义说明\n");

        assertEquals(1, update(file, "# Default language\nlanguage: zh_CN\n"));
        assertEquals("zh_CN", read(file).get("language"));
        String result = Files.readString(file);
        assertTrue(result.contains("User's instructions"));
        assertTrue(result.contains("自定义说明"));
        assertTrue(result.contains("Default language"));
    }

    @Test
    void createsMissingFileFromUtf8DefaultsAndSecondRunIsNoOp() throws Exception {
        Path file = directory.resolve("lang/zh_CN.yml");
        String defaults = "# 中文说明 / English comment\nmessage: 你好\n";

        assertEquals(1, update(file, defaults));
        assertEquals("你好", read(file).get("message"));
        assertTrue(Files.readString(file).contains("中文说明 / English comment"));
        String first = Files.readString(file);
        assertEquals(0, update(file, defaults));
        assertEquals(first, Files.readString(file));
    }

    @Test
    void existingLayoutAndListsArePreservedWhileBundledNewControlsAreInserted() throws Exception {
        Path file = write("browser:\n  layout: ['<CCCCCCC>', 'XXXXXXXXX', 'XXXXXXXXX', 'XXXXXXXXX', 'XXXXXXXXX', 'B#L#S#R##']\n");
        List<?> oldLayout = (List<?>) map(read(file), "browser").get("layout");

        try (InputStream bundled = getClass().getResourceAsStream("/resources/cer_menu_cute/menu/browse.yml")) {
            assertTrue(YamlDefaultsUpdater.update(file, bundled) > 0);
        }
        Map<String, Object> values = read(file);
        assertEquals(oldLayout, map(values, "browser").get("layout"));
        assertEquals("H", map(map(values, "content"), "hand").get("char"));
        assertEquals("I", map(map(values, "content"), "info").get("char"));
        assertEquals("T", map(map(values, "content"), "workstations").get("char"));
        try (InputStream bundled = getClass().getResourceAsStream("/resources/cer_menu_cute/menu/browse.yml")) {
            assertEquals(0, YamlDefaultsUpdater.update(file, bundled));
        }
    }

    @Test
    void missingBundledResourceLeavesOriginalFileUntouched() throws Exception {
        Path file = write("language: en_US\n");

        assertThrows(IOException.class, () -> YamlDefaultsUpdater.update(file, null));
        assertEquals("language: en_US\n", Files.readString(file));
        assertFalse(Files.isDirectory(directory.resolve("config.yml")));
    }

    private Path write(String value) throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return file;
    }

    private static int update(Path file, String defaults) throws Exception {
        return YamlDefaultsUpdater.update(file, stream(defaults));
    }

    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> read(Path file) throws IOException {
        return new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
